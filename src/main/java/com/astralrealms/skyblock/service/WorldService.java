package com.astralrealms.skyblock.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.event.island.world.IslandWorldLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldUnloadedEvent;
import com.astralrealms.skyblock.listener.IslandSettingsListener;
import com.astralrealms.skyblock.messaging.packet.island.IslandDeletePacket;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.utils.ASConstants;
import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.loaders.file.FileLoader;
import com.infernalsuite.asp.loaders.mysql.MysqlLoader;

public class WorldService {

    /** How long the shutdown host-server flush is allowed to block waiting on Redis. */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 5;
    /** How long shutdown waits for every world save to reach storage. */
    private static final long SHUTDOWN_SAVE_TIMEOUT_SECONDS = 60;
    private static final int SAVE_THREADS = 4;
    /** Idle-unload sweep cadence, in ticks (30s at 20 TPS). */
    private static final long IDLE_SWEEP_INTERVAL_TICKS = 20L * 30;
    /** Minimum gap between two fallback-group transfer requests for the same player. */
    private static final long TRANSFER_REQUEST_COOLDOWN_MILLIS = 60_000;
    /** The same gap where the transfer is the only way to free the world: shorter, but not absent. */
    private static final long URGENT_TRANSFER_REQUEST_COOLDOWN_MILLIS = 15_000;

    private final AstralSkyblock plugin;
    private final AdvancedSlimePaperAPI asp = AdvancedSlimePaperAPI.instance();
    private final Map<UUID, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();
    private final Map<String, UUID> worldNameToIslandId = new ConcurrentHashMap<>();

    // Coalesces concurrent load() calls for the same island so a world is never loaded twice.
    private final Map<UUID, CompletableFuture<SlimeWorldInstance>> loading = new ConcurrentHashMap<>();
    // First tick (millis) a loaded world was observed empty; cleared as soon as a player is present.
    private final Map<UUID, Long> emptySince = new ConcurrentHashMap<>();
    // Islands whose row is gone. Their world must never be written back — not by an unload, not by
    // the idle sweep, not by the shutdown flush, and it must never be loaded again.
    //
    // Entries are never removed while the server runs. Every attempt so far to release the mark at
    // some "safe" point has reopened the resurrection it prevents: releasing it when the world
    // unloads misses a refused unload, releasing it when storage is deleted misses a world still
    // resident, and releasing it on a no-op unload reopens the load window. An island id is never
    // reused, so holding a few UUIDs until restart costs nothing and closes all of those.
    private final Set<UUID> deleted = ConcurrentHashMap.newKeySet();
    // Last time each player was asked to move to the fallback group, so a retried evacuation does
    // not re-request a transfer for someone who is already on their way out.
    private final Map<UUID, Long> lastTransferRequest = new ConcurrentHashMap<>();

    // World saves still writing to storage, by island. A load or delete of that island waits for
    // it: reading before the write lands would bring back the previous state, and deleting before
    // it would let the write recreate the world.
    private final Map<UUID, CompletableFuture<Void>> saving = new ConcurrentHashMap<>();
    // Serialising and writing a world takes long enough to stall a tick; it never runs on the main
    // thread. A dedicated pool, because the Bukkit scheduler refuses new tasks during shutdown.
    private final ExecutorService saveExecutor = Executors.newFixedThreadPool(SAVE_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "AstralSkyblock-world-save");
        thread.setDaemon(true);
        return thread;
    });

    private final FileLoader sourceLoader;
    private MysqlLoader worldLoader;
    private BukkitTask idleSweepTask;

    public WorldService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.sourceLoader = new FileLoader(plugin.getDataPath().resolve("sourceWorlds").toFile());
    }

    /**
     * Initialises the MySQL ASP loader and (on island servers) starts the idle-unload sweep. Idempotent:
     * a second call — e.g. {@code /skyblock reload} re-running {@code loadConfiguration()} — is a no-op so
     * that reloading configuration never tears down the live loader or orphans loaded worlds against it.
     */
    public void load() {
        if (this.worldLoader != null) {
            this.plugin.getSLF4JLogger().debug("MySQL ASP loader already initialized; skipping re-init.");
            return;
        }

        this.plugin.getSLF4JLogger().info("Initializing mysql asp loader...");

        // Init loader
        try {
            ASPLoaderConfiguration configuration = this.plugin.aspLoaderConfiguration();
            this.worldLoader = new MysqlLoader(
                    configuration.sqlUrl(),
                    configuration.host(),
                    configuration.port(),
                    configuration.database(),
                    configuration.useSsl(),
                    configuration.username(),
                    configuration.password()
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize MySQL loader", e);
        }

        this.plugin.getSLF4JLogger().info("MySQL ASP loader initialized successfully.");

        // Idle-unload sweep (island servers only — only they host island worlds)
        if (this.plugin.configuration().isIslandServer())
            this.idleSweepTask = Bukkit.getScheduler().runTaskTimer(
                    this.plugin, this::sweepIdleWorlds, IDLE_SWEEP_INTERVAL_TICKS, IDLE_SWEEP_INTERVAL_TICKS);
    }

    public void unload() {
        // Stop the idle sweep first so it can't race the teardown.
        if (this.idleSweepTask != null) {
            this.idleSweepTask.cancel();
            this.idleSweepTask = null;
        }

        // Save every loaded world and clear its host-server mapping. deleteHostServer is async (Redis), so
        // collect the futures and block briefly — otherwise the process can exit before they flush, leaving
        // stale island->server entries that route players to a dead server.
        // Snapshots are taken here, on the main thread, then written in parallel: one after another
        // on the main thread, a full server could outlast the stop timeout and lose the rest.
        List<CompletableFuture<Void>> saves = new ArrayList<>(this.saving.values());
        List<UUID> flushed = new ArrayList<>();
        for (SlimeWorldInstance instance : loadedWorlds.values()) {
            UUID uniqueId = UUID.fromString(instance.getName());
            flushed.add(uniqueId);

            // A world whose island was deleted is skipped: saving it here would write back the row
            // the delete just removed, which is the resurrection the delete path exists to prevent.
            if (this.deleted.contains(uniqueId)) {
                this.plugin.getSLF4JLogger().info("Skipping the shutdown save of deleted island {}", uniqueId);
                continue;
            }
            saves.add(saveAsync(uniqueId, instance.getSerializableCopy()));
        }

        try {
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .get(SHUTDOWN_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.plugin.getSLF4JLogger().info("Flushed {} island world(s).", flushed.size());
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Not every island world was saved before shutdown", e);
        }

        List<CompletableFuture<Void>> hostCleanups = new ArrayList<>();
        for (UUID uniqueId : flushed)
            hostCleanups.add(this.plugin.servers()
                    .releaseHost(uniqueId, AstralPaperAPI.serverInformation().uniqueId())
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to delete host server for island with UUID: {}", uniqueId, throwable);
                        return null;
                    }));

        try {
            CompletableFuture.allOf(hostCleanups.toArray(CompletableFuture[]::new))
                    .get(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().warn("Timed out flushing host-server cleanup on shutdown", e);
        }
        this.saveExecutor.shutdown();

        this.loadedWorlds.clear();
        this.worldNameToIslandId.clear();
        this.emptySince.clear();
        this.loading.clear();
        this.deleted.clear();
        this.lastTransferRequest.clear();

        // Close loader
        if (this.worldLoader != null) {
            this.worldLoader.close();
            this.worldLoader = null;
        }
    }

    public CompletableFuture<SlimeWorldInstance> create(UUID uniqueId, IslandBlueprint blueprint) {
        // No save after loading: clone(name, loader) has already written the new world to storage,
        // and a save here ran on the main thread, stalling the tick on every island creation.
        return this.createNewWorld(uniqueId, blueprint)
                .thenCompose(clonedWorld -> this.loadWorld(uniqueId, clonedWorld))
                .exceptionallyCompose(throwable -> {
                    // Best-effort teardown of whatever got created so a failed creation never leaves an
                    // orphaned slime world (or a half-loaded Bukkit world) behind.
                    this.plugin.getSLF4JLogger().error("Creation failed for island {}; cleaning up partial world", uniqueId, throwable);
                    return this.delete(uniqueId, false)
                            .exceptionally(cleanupError -> {
                                this.plugin.getSLF4JLogger().error("Failed to clean up partial world for island {}", uniqueId, cleanupError);
                                return null;
                            })
                            .thenCompose(ignored -> CompletableFuture.failedFuture(unwrap(throwable)));
                });
    }

    public CompletableFuture<SlimeWorldInstance> load(Island island) {
        UUID id = island.uniqueId();

        // Its row is gone and its storage is being removed; loading it now would re-register an
        // unguarded world that the next save would write back.
        if (this.deleted.contains(id))
            return CompletableFuture.failedFuture(new IllegalStateException("Island is being deleted: " + id));

        // Already loaded — hand back the live instance instead of loading it a second time.
        SlimeWorldInstance existing = this.loadedWorlds.get(id);
        if (existing != null)
            return CompletableFuture.completedFuture(existing);

        // Coalesce concurrent loads (e.g. two visitors, or a visit racing a cross-server load request)
        // onto a single in-flight future so asp.loadWorld runs exactly once per island.
        CompletableFuture<SlimeWorldInstance> inFlight = new CompletableFuture<>();
        CompletableFuture<SlimeWorldInstance> prior = this.loading.putIfAbsent(id, inFlight);
        if (prior != null)
            return prior;
        // A load that finished between the check above and the putIfAbsent has already registered
        // its world and left the map: loading it again would only be refused by ASP.
        existing = this.loadedWorlds.get(id);
        if (existing != null) {
            this.loading.remove(id, inFlight);
            inFlight.complete(existing);
            return inFlight;
        }

        CompletableFuture<SlimeWorld> read = new CompletableFuture<>();
        // A save of this world still being written (it was just unloaded) must land first.
        pendingSave(id).whenComplete((ignored, saveError) -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (isTombstoned(id)) {
                this.deleted.add(id);
                read.completeExceptionally(new IllegalStateException("Island was deleted: " + id));
                return;
            }
            try {
                SlimePropertyMap propertyMap = buildPropertyMap(
                        (int) island.spawnX(),
                        (int) island.spawnY(),
                        (int) island.spawnZ(),
                        island.spawnYaw()
                );
                read.complete(asp.readWorld(this.worldLoader, id.toString(), false, propertyMap));
            } catch (UnknownWorldException | IOException | CorruptedWorldException | NewerFormatException e) {
                read.completeExceptionally(e);
            }
        }));

        read.thenCompose(world -> this.loadWorld(id, world))
                .whenComplete((instance, throwable) -> {
                    this.loading.remove(id);
                    if (throwable != null) {
                        // Hand the island back so another request can place it elsewhere.
                        releaseLoadingClaim(id);
                        inFlight.completeExceptionally(throwable);
                    } else
                        inFlight.complete(instance);
                });
        return inFlight;
    }

    private CompletableFuture<SlimeWorldInstance> loadWorld(UUID id, SlimeWorld world) {
        CompletableFuture<SlimeWorldInstance> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            SlimeWorldInstance instance;
            try {
                // Load the world instance
                instance = asp.loadWorld(world, true);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
                return;
            }

            try {
                // The island may have been deleted while its world was being read from storage:
                // load() only checks that at entry. Registering now would publish a host mapping the
                // delete just cleared and route visitors to an island that no longer exists.
                if (this.deleted.contains(id))
                    throw new IllegalStateException("Island was deleted while its world was loading: " + id);

                // Register the loaded world instance
                this.loadedWorlds.put(id, instance);
                this.worldNameToIslandId.put(instance.getName(), id);

                // Apply environment settings to the world based on the island's settings
                Island island = this.plugin.islands()
                        .repository()
                        .findCachedById(id)
                        .orElseThrow(() -> new IllegalStateException("Island not found for UUID: " + id));
                IslandSettingsListener.applyEnvironment(island, instance.getBukkitWorld());

                // Record this server as the host. It only succeeds while nobody else holds the island:
                // a copy that lost that race is dropped before anyone can play on it, or both copies
                // would be saved and one would overwrite the other.
                this.plugin.servers()
                        .confirmHost(id, AstralPaperAPI.serverInformation().uniqueId())
                        .whenComplete((confirmed, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null)
                                // Redis is unreachable: nobody else can claim it either, so keep serving it.
                                plugin.getSLF4JLogger().error("Failed to record this server as host of island {}", id, throwable);
                            else if (!confirmed) {
                                discard(id, instance);
                                future.completeExceptionally(new IllegalStateException("Island " + id + " is already hosted by another server"));
                                return;
                            }

                            new IslandWorldLoadedEvent(island, instance.getBukkitWorld()).callEvent();
                            this.plugin.servers().refreshHeartbeat();
                            future.complete(instance);
                        }));
            } catch (Exception ex) {
                // Roll back the partially-registered world so a post-load failure can't leak a world that
                // stays resident in Bukkit and the maps while the caller sees a failure.
                discard(id, instance);
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    /**
     * Writes a world snapshot to storage on {@link #saveExecutor}, unless its island has been
     * deleted by then. Tracked in {@link #saving} until it lands.
     */
    private CompletableFuture<Void> saveAsync(UUID id, SlimeWorld snapshot) {
        CompletableFuture<Void> save = CompletableFuture.runAsync(() -> {
            if (!this.deleted.contains(id) && isTombstoned(id))
                this.deleted.add(id);
            if (this.deleted.contains(id)) {
                this.plugin.getSLF4JLogger().info("Skipping the save of deleted island {}", id);
                return;
            }
            try {
                this.asp.saveWorld(snapshot);
            } catch (IOException e) {
                throw new CompletionException("Failed to save world for island " + id, e);
            }
        }, this.saveExecutor);
        this.saving.put(id, save);
        save.whenComplete((ignored, throwable) -> {
            this.saving.remove(id, save);
            if (throwable != null)
                this.plugin.getSLF4JLogger().error("Failed to save the world of island {}", id, throwable);
        });
        return save;
    }

    /**
     * Whether {@code id} was deleted network-wide. Blocking (bounded); only called off the main
     * thread. When Redis cannot answer, assumes not — refusing to save on a hiccup would lose data.
     */
    private boolean isTombstoned(UUID id) {
        try {
            return Boolean.TRUE.equals(this.plugin.servers().isDeleted(id).get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().warn("Could not check whether island {} was deleted", id, e);
            return false;
        }
    }

    /** The save of {@code id} still being written, as a future that never fails; done when there is none. */
    private CompletableFuture<Void> pendingSave(UUID id) {
        CompletableFuture<Void> save = this.saving.get(id);
        return save == null ? CompletableFuture.completedFuture(null) : save.exceptionally(ignored -> null);
    }

    private void releaseLoadingClaim(UUID id) {
        this.plugin.servers()
                .releaseLoadingClaim(id, AstralPaperAPI.serverInformation().uniqueId())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to release the host claim on island {} after a failed load", id, throwable);
                    return null;
                });
    }

    /** Unregisters and unloads, without saving, a world that must not be kept. Main thread only. */
    private void discard(UUID id, SlimeWorldInstance instance) {
        this.loadedWorlds.remove(id);
        this.worldNameToIslandId.remove(instance.getName());
        try {
            Bukkit.unloadWorld(instance.getBukkitWorld(), false);
        } catch (Exception unloadError) {
            this.plugin.getSLF4JLogger().error("Failed to roll back partially loaded world for island {}", id, unloadError);
        }
    }

    private CompletableFuture<SlimeWorld> createNewWorld(UUID uniqueId, IslandBlueprint blueprint) {
        CompletableFuture<SlimeWorld> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SlimePropertyMap propertyMap = buildPropertyMap(
                        (int) blueprint.spawnLocation().x(),
                        (int) blueprint.spawnLocation().y(),
                        (int) blueprint.spawnLocation().z(),
                        blueprint.spawnLocation().yaw()
                );

                SlimeWorld sourceWorld = this.asp.readWorld(this.sourceLoader, blueprint.sourceWorld().replace(".slime", ""), false, propertyMap);
                future.complete(sourceWorld.clone(uniqueId.toString(), this.worldLoader));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private SlimePropertyMap buildPropertyMap(int x, int y, int z, float yaw) {
        SlimePropertyMap propertyMap = new SlimePropertyMap();
        propertyMap.setValue(SlimeProperties.SPAWN_X, x);
        propertyMap.setValue(SlimeProperties.SPAWN_Y, y);
        propertyMap.setValue(SlimeProperties.SPAWN_Z, z);
        propertyMap.setValue(SlimeProperties.SPAWN_YAW, yaw);

        propertyMap.setValue(SlimeProperties.DIFFICULTY, "normal");
        propertyMap.setValue(SlimeProperties.ALLOW_MONSTERS, true);
        propertyMap.setValue(SlimeProperties.ALLOW_ANIMALS, true);
        propertyMap.setValue(SlimeProperties.DRAGON_BATTLE, false);
        propertyMap.setValue(SlimeProperties.PVP, false);
        propertyMap.setValue(SlimeProperties.ENVIRONMENT, "NORMAL");
        propertyMap.setValue(SlimeProperties.WORLD_TYPE, "DEFAULT");
        propertyMap.setValue(SlimeProperties.DEFAULT_BIOME, "minecraft:plains");
        propertyMap.setValue(SlimeProperties.SAVE_BLOCK_TICKS, false);
        propertyMap.setValue(SlimeProperties.SAVE_FLUID_TICKS, false);
        propertyMap.setValue(SlimeProperties.SAVE_POI, false);
        propertyMap.setValue(SlimeProperties.SEA_LEVEL, SlimeProperties.SEA_LEVEL.getDefaultValue());

        return propertyMap;
    }

    public CompletableFuture<Void> unload(UUID uniqueId) {
        return this.unload(uniqueId, true);
    }

    /**
     * Unloads an island world from this server. When {@code save} is {@code false} the world is dropped
     * without persisting — used by the delete path so a world can't be re-written back to MySQL after its
     * row has been removed.
     */
    public CompletableFuture<Void> unload(UUID uniqueId, boolean save) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            this.emptySince.remove(uniqueId);

            World bukkitWorld = Bukkit.getWorld(uniqueId.toString());
            if (bukkitWorld == null) {
                // Not loaded on this server; drop any stale registrations just in case.
                this.loadedWorlds.remove(uniqueId);
                this.worldNameToIslandId.remove(uniqueId.toString());
                future.complete(null);
                return;
            }

            // A deleted island can never be saved, whatever the caller asked for: the storage row is
            // already gone, and writing it back would resurrect the island.
            boolean effectiveSave = save && !this.deleted.contains(uniqueId);

            // Bukkit refuses to unload a world that still holds players, which is the normal case for
            // a disband (typed while standing on the island). Move them out first, then send them on
            // to the fallback group — the local hop is what actually frees the world.
            evacuate(bukkitWorld);

            // Snapshot now, write later: serialising and writing the world on the main thread stalls
            // the tick. Bukkit itself is told not to save.
            SlimeWorldInstance instance = this.loadedWorlds.get(uniqueId);
            SlimeWorld snapshot = effectiveSave && instance != null ? instance.getSerializableCopy() : null;

            // Unload world
            boolean success = Bukkit.unloadWorld(bukkitWorld, false);
            if (!success) {
                future.completeExceptionally(new IllegalStateException("Bukkit refused to unload world for island with UUID: " + uniqueId));
                return;
            }

            // Remove from loaded worlds and world name mapping
            this.loadedWorlds.remove(uniqueId);
            this.worldNameToIslandId.remove(uniqueId.toString());

            // The island is only handed back once its save has landed: another server picking it
            // up earlier would load the state from before this session.
            CompletableFuture<Void> saved = snapshot == null
                    ? CompletableFuture.completedFuture(null)
                    : saveAsync(uniqueId, snapshot);
            this.plugin.servers().refreshHeartbeat();
            saved.handle((ignored, saveError) -> this.plugin.servers()
                            .releaseHost(uniqueId, AstralPaperAPI.serverInformation().uniqueId()))
                    .thenCompose(release -> release)
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to delete host server for island with UUID: {}", uniqueId, throwable);
                        return null;
                    });

            // Throw event
            Island island = this.plugin.islands()
                    .repository()
                    .findCachedById(uniqueId)
                    .orElse(null);
            if (island != null)
                new IslandWorldUnloadedEvent(island, bukkitWorld).callEvent();
            else
                this.plugin.getSLF4JLogger().warn("Island not found for UUID: {} when unloading world.", uniqueId);


            future.complete(null);
        });
        return future;
    }

    /**
     * Drops a world whose island was deleted on another server: marks it write-protected here, then
     * unloads it without saving. The mark is what makes the difference — a refused unload (players
     * still on the island) leaves the world registered, and without it the idle sweep would later
     * unload it *with saving* and the shutdown flush would write it back, resurrecting the island.
     */
    public CompletableFuture<Void> dropDeleted(UUID uniqueId) {
        this.deleted.add(uniqueId);
        return unload(uniqueId, false);
    }

    public CompletableFuture<Void> delete(UUID uniqueId) {
        return delete(uniqueId, true);
    }

    /**
     * @param tombstone whether to record the deletion network-wide. Only a creation that failed
     *                  skips it: the island may yet be created on another server, whose saves the
     *                  tombstone would silently refuse.
     */
    private CompletableFuture<Void> delete(UUID uniqueId, boolean tombstone) {
        // From here on this island's world is write-protected everywhere in this service.
        this.deleted.add(uniqueId);

        // Recorded first, so that a host which misses the broadcast below (its queue was
        // reconnecting) still refuses to save the world back, and drops it on its next sweep.
        if (tombstone)
            this.plugin.servers()
                    .markDeleted(uniqueId)
                    .exceptionally(throwable -> {
                        this.plugin.getSLF4JLogger().error("Failed to record the deletion of island {}", uniqueId, throwable);
                        return null;
                    });

        // Tell whichever server currently hosts this world to drop it without saving. We are echo-suppressed
        // from our own broadcast, so the local host (if any) is handled by the unload(false) below.
        this.plugin.messaging()
                .send(ASConstants.ISLAND_MANAGEMENT_CHANNEL, new IslandDeletePacket(uniqueId))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to broadcast island delete for {}", uniqueId, throwable);
                    return null;
                });

        return this.unload(uniqueId, false)
                // A refused unload must not abort the deletion: the row is already gone, so leaving the
                // slime world in storage would orphan it forever. Log and carry on to the storage delete.
                .handle((ignored, throwable) -> {
                    if (throwable != null)
                        // The world stays registered on purpose: the idle sweep retries it, and the
                        // deleted set guarantees that neither that retry nor the shutdown flush can
                        // save it in the meantime.
                        this.plugin.getSLF4JLogger().error("Failed to unload world for island {} before deleting it; "
                                                           + "deleting it from storage anyway", uniqueId, throwable);
                    return null;
                })
                .thenCompose(ignored -> this.plugin.servers().clearHost(uniqueId))
                // A save still being written would recreate the world right after it is deleted.
                .thenCompose(ignored -> pendingSave(uniqueId))
                .thenRunAsync(() -> {
                    try {
                        this.worldLoader.deleteWorld(uniqueId.toString());
                    } catch (UnknownWorldException e) {
                        // Nothing persisted (e.g. a creation that failed before the clone) — treat as deleted.
                        this.plugin.getSLF4JLogger().debug("World for island {} was not present in storage on delete", uniqueId);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to delete world for island with UUID: " + uniqueId, e);
                    }
                }, this.saveExecutor); // JDBC: not on the shared ForkJoin pool
    }

    /**
     * Moves every player out of {@code world} so it can be unloaded: a local teleport to this
     * server's main world spawn (immediate, which is what Bukkit needs), followed by a best-effort
     * transfer to the configured fallback group. Must run on the main thread.
     */
    private void evacuate(World world) {
        List<Player> players = List.copyOf(world.getPlayers());
        if (players.isEmpty())
            return;

        // The main world by preference, then any other non-island world. Never another island: that
        // would drop evacuees onto somebody else's island past its ban and visit checks.
        World fallback = Bukkit.getWorlds().stream()
                .filter(candidate -> !candidate.equals(world))
                .filter(candidate -> !this.worldNameToIslandId.containsKey(candidate.getName()))
                .findFirst()
                .orElse(null);
        if (fallback == null)
            // No local destination: the transfer below still moves them, but not within this tick,
            // so this unload will be refused and retried by the sweep.
            this.plugin.getSLF4JLogger().warn("No non-island world to evacuate {} players from {} into",
                    players.size(), world.getName());
        this.plugin.getSLF4JLogger().info("Evacuating {} player(s) from island world {}", players.size(), world.getName());

        World destination = fallback;
        for (Player player : players) {
            if (destination != null)
                player.teleport(destination.getSpawnLocation());

            // A refused unload is retried every sweep. The local hop above is cheap and repeatable,
            // but a cross-server transfer is not: ask for one per player at most once a minute, so
            // somebody who joins the world mid-retry still gets one and nobody gets spammed. With no
            // local destination the transfer is the only thing that can free the world, so it is
            // asked for more often — but still throttled, or a failing fallback group would mean a
            // fresh transfer request per player on every sweep, forever.
            long now = System.currentTimeMillis();
            long cooldown = destination == null
                    ? URGENT_TRANSFER_REQUEST_COOLDOWN_MILLIS
                    : TRANSFER_REQUEST_COOLDOWN_MILLIS;
            Long requested = this.lastTransferRequest.get(player.getUniqueId());
            if (requested != null && now - requested < cooldown)
                continue;
            this.lastTransferRequest.put(player.getUniqueId(), now);

            AstralPaperAPI.getService(TeleportationService.class)
                    .orElseThrow()
                    .sendToGroup(player.getUniqueId(), this.plugin.configuration().fallbackGroup())
                    .exceptionally(throwable -> {
                        // The request never happened, so it must not hold the cooldown: the next
                        // sweep should be free to ask again. Only this request's own timestamp is
                        // withdrawn — a slow failure must not clear a newer request's.
                        this.lastTransferRequest.remove(player.getUniqueId(), now);
                        this.plugin.getSLF4JLogger().error("Failed to send {} to the fallback group after evacuating {}",
                                player.getName(), world.getName(), throwable);
                        return null;
                    });
        }
    }

    /**
     * Runs on the main thread every {@link #IDLE_SWEEP_INTERVAL_TICKS} ticks: unloads any island world that
     * has had no players for at least the configured grace period, freeing memory and the server's island
     * slot. A world that regains a player before the grace elapses is spared and its timer reset.
     */
    private void sweepIdleWorlds() {
        // A deleted island whose unload was refused earlier (players still inside) is retried on
        // every sweep, regardless of the idle settings — it is not idleness that must free it.
        for (UUID id : List.copyOf(this.deleted)) {
            if (!this.loadedWorlds.containsKey(id))
                continue;
            this.plugin.getSLF4JLogger().debug("Retrying the unload of deleted island world {}", id);
            this.unload(id, false).exceptionally(throwable -> {
                this.plugin.getSLF4JLogger().error("Failed to unload deleted island world {}", id, throwable);
                return null;
            });
        }

        // Expired cooldowns carry no information; dropping them keeps the map bounded by the players
        // evacuated in the last minute rather than by every player ever evacuated.
        long staleBefore = System.currentTimeMillis() - TRANSFER_REQUEST_COOLDOWN_MILLIS;
        this.lastTransferRequest.values().removeIf(requested -> requested < staleBefore);

        int idleSeconds = this.plugin.configuration().worldIdleUnloadSeconds();
        if (idleSeconds < 0)
            return; // idle unloading disabled

        long now = System.currentTimeMillis();
        long graceMillis = idleSeconds * 1000L;

        for (Map.Entry<UUID, SlimeWorldInstance> entry : this.loadedWorlds.entrySet()) {
            UUID id = entry.getKey();
            if (this.deleted.contains(id))
                continue; // handled above

            World world = entry.getValue().getBukkitWorld();
            if (world == null || !world.getPlayers().isEmpty()) {
                this.emptySince.remove(id);
                continue;
            }

            Long since = this.emptySince.putIfAbsent(id, now);
            if (since == null)
                continue; // first sweep seeing it empty — start the clock

            if (now - since >= graceMillis)
                unloadIfStillIdle(id, now - since);
        }

        // Islands deleted while this server missed the broadcast: their tombstone is the only
        // sign. Checked off the main thread, acted on back on it.
        for (UUID id : List.copyOf(this.loadedWorlds.keySet())) {
            if (this.deleted.contains(id))
                continue;
            this.plugin.servers()
                    .isDeleted(id)
                    .thenAccept(deletedElsewhere -> {
                        if (Boolean.TRUE.equals(deletedElsewhere))
                            Bukkit.getScheduler().runTask(this.plugin, () -> {
                                this.plugin.getSLF4JLogger().warn("Island {} was deleted on another server; dropping its world", id);
                                dropDeleted(id);
                            });
                    });
        }
    }

    /**
     * Unloads an idle world, unless a player turned up after the sweep looked or is being sent
     * here right now — the host mapping still points at this server, so another server may be
     * transferring someone in, and unloading under them would leave them nowhere to land.
     */
    private void unloadIfStillIdle(UUID id, long idleMillis) {
        this.plugin.servers()
                .isArriving(id)
                .exceptionally(throwable -> false)
                .thenAccept(arriving -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    SlimeWorldInstance instance = this.loadedWorlds.get(id);
                    if (instance == null || this.deleted.contains(id))
                        return;
                    if (arriving || !instance.getBukkitWorld().getPlayers().isEmpty()) {
                        this.emptySince.remove(id); // start the idle clock over
                        return;
                    }

                    this.emptySince.remove(id);
                    this.plugin.getSLF4JLogger().info("Unloading idle island world {} (empty for {}s)", id, idleMillis / 1000);
                    this.unload(id).exceptionally(throwable -> {
                        this.plugin.getSLF4JLogger().error("Failed to unload idle island world {}", id, throwable);
                        return null;
                    });
                }));
    }

    /** The island whose world this is, whether or not the island itself is cached here. */
    public Optional<UUID> findIslandIdByWorld(World world) {
        return Optional.ofNullable(worldNameToIslandId.get(world.getName()));
    }

    public Optional<Island> findByWorld(World world) {
        UUID uniqueId = worldNameToIslandId.get(world.getName());
        if (uniqueId == null)
            return Optional.empty();
        return plugin.islands()
                .repository()
                .findCachedById(uniqueId);
    }

    public Optional<SlimeWorldInstance> findByIslandId(UUID uniqueId) {
        return Optional.ofNullable(loadedWorlds.get(uniqueId));
    }

    @Unmodifiable
    public Map<UUID, SlimeWorldInstance> getLoadedWorlds() {
        return Map.copyOf(loadedWorlds);
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }
}
