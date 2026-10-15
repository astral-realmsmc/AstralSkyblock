package com.astralrealms.skyblock.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.event.island.world.IslandDimensionLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldUnloadedEvent;
import com.astralrealms.skyblock.listener.IslandSettingsListener;
import com.astralrealms.skyblock.messaging.packet.island.IslandDeletePacket;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.dimension.IslandWorlds;
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

/**
 * The slime worlds of the islands hosted here. An island has its overworld and, when dimensions are
 * enabled, a world per dimension it has entered at least once ({@link IslandWorlds} names them).
 *
 * <p>An island's worlds live together: the dimensions it already has load with its overworld, are
 * saved with it, idle out with it (the island is idle only when all of them are empty) and are
 * deleted with it. A dimension it never had is cloned from its source world the first time someone
 * enters it ({@link #loadDimension}), and only while the overworld is loaded here — the host claim
 * covers every world of the island.
 */
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
    // Island overworlds, one per hosted island: their count is the number of islands hosted here.
    private final Map<UUID, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();
    // Dimension worlds of the hosted islands, by island then dimension id.
    private final Map<UUID, Map<String, SlimeWorldInstance>> dimensionWorlds = new ConcurrentHashMap<>();
    // Every island world loaded here, overworld or dimension.
    private final Map<String, UUID> worldNameToIslandId = new ConcurrentHashMap<>();
    // Dimension worlds only, to their dimension id.
    private final Map<String, String> worldNameToDimension = new ConcurrentHashMap<>();
    // Dimensions an island has in storage that are not loaded with it (a read that failed, an unload
    // that stopped half-way). Its blocks cannot be seen, so its level is not rescanned meanwhile.
    private final Map<UUID, Set<String>> missingDimensions = new ConcurrentHashMap<>();

    // Coalesces concurrent load() calls for the same island so a world is never loaded twice.
    private final Map<UUID, CompletableFuture<SlimeWorldInstance>> loading = new ConcurrentHashMap<>();
    // The same for dimensions entered after their island loaded, by world name.
    private final Map<String, CompletableFuture<SlimeWorldInstance>> loadingDimensions = new ConcurrentHashMap<>();
    // First tick (millis) a loaded island was observed empty; cleared as soon as a player is present.
    private final Map<UUID, Long> emptySince = new ConcurrentHashMap<>();
    // Islands whose row is gone. Their worlds must never be written back — not by an unload, not by
    // the idle sweep, not by the shutdown flush, and they must never be loaded again.
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

    // World saves still writing to storage, by world name. A load or delete of that world waits for
    // it: reading before the write lands would bring back the previous state, and deleting before
    // it would let the write recreate the world.
    private final Map<String, CompletableFuture<Void>> saving = new ConcurrentHashMap<>();
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
        List<UUID> flushed = new ArrayList<>(this.loadedWorlds.keySet());
        for (UUID uniqueId : flushed) {
            // A world whose island was deleted is skipped: saving it here would write back the row
            // the delete just removed, which is the resurrection the delete path exists to prevent.
            if (this.deleted.contains(uniqueId)) {
                this.plugin.getSLF4JLogger().info("Skipping the shutdown save of deleted island {}", uniqueId);
                continue;
            }
            for (SlimeWorldInstance instance : islandInstances(uniqueId))
                saves.add(saveAsync(uniqueId, instance.getSerializableCopy()));
        }

        try {
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .get(SHUTDOWN_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.plugin.getSLF4JLogger().info("Flushed {} island(s).", flushed.size());
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
        this.dimensionWorlds.clear();
        this.worldNameToIslandId.clear();
        this.worldNameToDimension.clear();
        this.missingDimensions.clear();
        this.emptySince.clear();
        this.loading.clear();
        this.loadingDimensions.clear();
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
        // A new island has no dimension yet: each is cloned the first time someone enters it.
        return this.createNewWorld(uniqueId, blueprint)
                .thenCompose(clonedWorld -> this.loadWorld(uniqueId, clonedWorld, Map.of()))
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

        CompletableFuture<StoredIsland> read = new CompletableFuture<>();
        // A save of this island still being written (it was just unloaded) must land first.
        pendingSaves(id).whenComplete((ignored, saveError) -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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
                SlimeWorld overworld = asp.readWorld(this.worldLoader, id.toString(), false, propertyMap);
                read.complete(new StoredIsland(overworld, readDimensions(id)));
            } catch (UnknownWorldException | IOException | CorruptedWorldException | NewerFormatException e) {
                read.completeExceptionally(e);
            }
        }));

        read.thenCompose(stored -> this.loadWorld(id, stored.overworld(), stored.dimensions()))
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

    /**
     * Reads the dimension worlds the island already has, off the main thread. One that cannot be read
     * is left out and remembered as missing; entering it later tries again.
     */
    private Map<String, SlimeWorld> readDimensions(UUID id) {
        // Whatever a previous, failed load noted is replaced by what this one finds.
        this.missingDimensions.remove(id);
        Map<String, SlimeWorld> worlds = new LinkedHashMap<>();
        if (!this.plugin.dimensions().enabled())
            return worlds;

        for (IslandDimension dimension : this.plugin.dimensions().all()) {
            String name = IslandWorlds.worldName(id, dimension.id());
            try {
                if (!this.worldLoader.worldExists(name))
                    continue;
                worlds.put(dimension.id(), withDimensionProperties(
                        asp.readWorld(this.worldLoader, name, false, buildPropertyMap(dimension)), dimension));
            } catch (Exception e) {
                this.plugin.getSLF4JLogger().error("Failed to read the {} world of island {}; it stays unloaded", dimension.id(), id, e);
                this.missingDimensions.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(dimension.id());
            }
        }
        return worlds;
    }

    private CompletableFuture<SlimeWorldInstance> loadWorld(UUID id, SlimeWorld world, Map<String, SlimeWorld> dimensions) {
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
                applyWorldRules(instance.getBukkitWorld());

                // Its dimensions come up with it. One that fails is left out rather than failing the
                // island: the overworld is what players are waiting for.
                dimensions.forEach((dimension, dimensionWorld) -> {
                    try {
                        SlimeWorldInstance dimensionInstance = asp.loadWorld(dimensionWorld, true);
                        registerDimension(id, dimension, dimensionInstance);
                        applyWorldRules(dimensionInstance.getBukkitWorld());
                    } catch (Exception ex) {
                        this.plugin.getSLF4JLogger().error("Failed to load the {} world of island {}; it stays unloaded", dimension, id, ex);
                        this.missingDimensions.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(dimension);
                    }
                });

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
                                discard(id);
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
                discard(id);
                // Not yet registered when the failure came before its registration.
                discardWorld(id, instance);
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    /**
     * Loads one of the island's dimensions — cloning it from its source world when the island never
     * had it — for a player about to enter it. Only on the server hosting the island, and only while
     * its overworld is loaded: a dimension never outlives it. Coalesced like {@link #load}.
     */
    public CompletableFuture<SlimeWorldInstance> loadDimension(Island island, IslandDimension dimension) {
        UUID id = island.uniqueId();
        String name = IslandWorlds.worldName(id, dimension.id());

        if (this.deleted.contains(id))
            return CompletableFuture.failedFuture(new IllegalStateException("Island is being deleted: " + id));
        if (!this.loadedWorlds.containsKey(id))
            return CompletableFuture.failedFuture(new IllegalStateException("Island " + id + " is not hosted on this server"));

        SlimeWorldInstance existing = dimensionInstance(id, dimension.id());
        if (existing != null)
            return CompletableFuture.completedFuture(existing);

        CompletableFuture<SlimeWorldInstance> inFlight = new CompletableFuture<>();
        CompletableFuture<SlimeWorldInstance> prior = this.loadingDimensions.putIfAbsent(name, inFlight);
        if (prior != null)
            return prior;
        existing = dimensionInstance(id, dimension.id());
        if (existing != null) {
            this.loadingDimensions.remove(name, inFlight);
            inFlight.complete(existing);
            return inFlight;
        }

        CompletableFuture<SlimeWorld> read = new CompletableFuture<>();
        pendingSave(name).whenComplete((ignored, saveError) -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (isTombstoned(id)) {
                this.deleted.add(id);
                read.completeExceptionally(new IllegalStateException("Island was deleted: " + id));
                return;
            }
            try {
                SlimePropertyMap propertyMap = buildPropertyMap(dimension);
                SlimeWorld world;
                try {
                    world = asp.readWorld(this.worldLoader, name, false, propertyMap);
                } catch (UnknownWorldException unknown) {
                    // First visit: the island gets its own copy of the dimension's source world.
                    this.plugin.getSLF4JLogger().info("Creating the {} world of island {}", dimension.id(), id);
                    SlimeWorld source = asp.readWorld(this.sourceLoader, dimension.sourceWorld().replace(".slime", ""), false, propertyMap);
                    world = source.clone(name, this.worldLoader);
                }
                read.complete(withDimensionProperties(world, dimension));
            } catch (Exception e) {
                read.completeExceptionally(e);
            }
        }));

        read.thenCompose(world -> registerEnteredDimension(island, dimension, world))
                .whenComplete((instance, throwable) -> {
                    this.loadingDimensions.remove(name);
                    if (throwable != null)
                        inFlight.completeExceptionally(throwable);
                    else
                        inFlight.complete(instance);
                });
        return inFlight;
    }

    private CompletableFuture<SlimeWorldInstance> registerEnteredDimension(Island island, IslandDimension dimension, SlimeWorld world) {
        UUID id = island.uniqueId();
        CompletableFuture<SlimeWorldInstance> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            SlimeWorldInstance instance;
            try {
                instance = asp.loadWorld(world, true);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
                return;
            }

            // The island may have been unloaded or deleted while this world was being read; it
            // must not stay behind on its own, outside the host claim.
            if (this.deleted.contains(id) || !this.loadedWorlds.containsKey(id)) {
                discardWorld(id, instance);
                future.completeExceptionally(new IllegalStateException("Island " + id + " left this server while its "
                                                                       + dimension.id() + " world was loading"));
                return;
            }

            registerDimension(id, dimension.id(), instance);
            applyWorldRules(instance.getBukkitWorld());
            try {
                new IslandDimensionLoadedEvent(island, dimension, instance.getBukkitWorld()).callEvent();
            } catch (Exception ex) {
                this.plugin.getSLF4JLogger().error("A listener failed on the {} world of island {} loading", dimension.id(), id, ex);
            }
            future.complete(instance);
        });
        return future;
    }

    /**
     * Forces the dimension's environment onto a world read from storage. ASP lets the properties
     * stored in a world win over the ones it is read with, so a source world saved as an ordinary
     * world would otherwise become an overworld under a nether's name.
     */
    private static SlimeWorld withDimensionProperties(SlimeWorld world, IslandDimension dimension) {
        world.getPropertyMap().setValue(SlimeProperties.ENVIRONMENT, dimension.slimeEnvironment());
        world.getPropertyMap().setValue(SlimeProperties.DRAGON_BATTLE, false);
        return world;
    }

    private void registerDimension(UUID id, String dimension, SlimeWorldInstance instance) {
        this.dimensionWorlds.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>()).put(dimension, instance);
        this.worldNameToIslandId.put(instance.getName(), id);
        this.worldNameToDimension.put(instance.getName(), dimension);
        Set<String> missing = this.missingDimensions.get(id);
        if (missing != null)
            missing.remove(dimension);
    }

    private void unregisterDimension(UUID id, String dimension, SlimeWorldInstance instance) {
        Map<String, SlimeWorldInstance> dimensions = this.dimensionWorlds.get(id);
        if (dimensions != null) {
            dimensions.remove(dimension, instance);
            if (dimensions.isEmpty())
                this.dimensionWorlds.remove(id, dimensions);
        }
        this.worldNameToIslandId.remove(instance.getName());
        this.worldNameToDimension.remove(instance.getName());
    }

    private void applyWorldRules(World world) {
        world.setGameRule(GameRules.KEEP_INVENTORY, this.plugin.configuration().keepInventory());
    }

    /**
     * Writes a world snapshot to storage on {@link #saveExecutor}, unless its island has been
     * deleted by then. Tracked in {@link #saving} until it lands.
     */
    private CompletableFuture<Void> saveAsync(UUID id, SlimeWorld snapshot) {
        String name = snapshot.getName();
        CompletableFuture<Void> save = CompletableFuture.runAsync(() -> {
            if (!this.deleted.contains(id) && isTombstoned(id))
                this.deleted.add(id);
            if (this.deleted.contains(id)) {
                this.plugin.getSLF4JLogger().info("Skipping the save of deleted island {} ({})", id, name);
                return;
            }
            try {
                this.asp.saveWorld(snapshot);
            } catch (IOException e) {
                throw new CompletionException("Failed to save world " + name + " of island " + id, e);
            }
        }, this.saveExecutor);
        this.saving.put(name, save);
        save.whenComplete((ignored, throwable) -> {
            this.saving.remove(name, save);
            if (throwable != null)
                this.plugin.getSLF4JLogger().error("Failed to save the world {} of island {}", name, id, throwable);
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

    /** The save of one world still being written, as a future that never fails; done when there is none. */
    private CompletableFuture<Void> pendingSave(String worldName) {
        CompletableFuture<Void> save = this.saving.get(worldName);
        return save == null ? CompletableFuture.completedFuture(null) : save.exceptionally(ignored -> null);
    }

    /** Every save of the island's worlds still being written, as a future that never fails. */
    private CompletableFuture<Void> pendingSaves(UUID id) {
        CompletableFuture<?>[] saves = this.saving.entrySet().stream()
                .filter(entry -> IslandWorlds.belongsTo(entry.getKey(), id))
                .map(entry -> entry.getValue().exceptionally(ignored -> null))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(saves);
    }

    private void releaseLoadingClaim(UUID id) {
        this.plugin.servers()
                .releaseLoadingClaim(id, AstralPaperAPI.serverInformation().uniqueId())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to release the host claim on island {} after a failed load", id, throwable);
                    return null;
                });
    }

    /** Unregisters and unloads, without saving, every world of an island that must not be kept. Main thread only. */
    private void discard(UUID id) {
        SlimeWorldInstance overworld = this.loadedWorlds.remove(id);
        Map<String, SlimeWorldInstance> dimensions = this.dimensionWorlds.remove(id);
        if (dimensions != null)
            dimensions.forEach((dimension, instance) -> {
                this.worldNameToDimension.remove(instance.getName());
                discardWorld(id, instance);
            });
        if (overworld != null)
            discardWorld(id, overworld);
        this.missingDimensions.remove(id);
    }

    /** Unloads one world without saving it. Main thread only. */
    private void discardWorld(UUID id, SlimeWorldInstance instance) {
        this.worldNameToIslandId.remove(instance.getName());
        try {
            if (Bukkit.getWorld(instance.getName()) != null)
                Bukkit.unloadWorld(instance.getBukkitWorld(), false);
        } catch (Exception unloadError) {
            this.plugin.getSLF4JLogger().error("Failed to roll back partially loaded world {} of island {}", instance.getName(), id, unloadError);
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
        return buildPropertyMap(x, y, z, yaw, "normal", "minecraft:plains");
    }

    private SlimePropertyMap buildPropertyMap(IslandDimension dimension) {
        MinecraftLocation spawn = dimension.spawnLocation();
        return buildPropertyMap((int) spawn.x(), (int) spawn.y(), (int) spawn.z(), spawn.yaw(),
                dimension.slimeEnvironment(), dimension.defaultBiome());
    }

    private SlimePropertyMap buildPropertyMap(int x, int y, int z, float yaw, String environment, String biome) {
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
        propertyMap.setValue(SlimeProperties.ENVIRONMENT, environment);
        propertyMap.setValue(SlimeProperties.WORLD_TYPE, "DEFAULT");
        propertyMap.setValue(SlimeProperties.DEFAULT_BIOME, biome);
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
     * Unloads an island's worlds from this server, its dimensions first. When {@code save} is
     * {@code false} they are dropped without persisting — used by the delete path so a world can't be
     * re-written back to MySQL after its row has been removed.
     */
    public CompletableFuture<Void> unload(UUID uniqueId, boolean save) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            this.emptySince.remove(uniqueId);

            World bukkitWorld = Bukkit.getWorld(uniqueId.toString());
            Map<String, SlimeWorldInstance> dimensions = Map.copyOf(this.dimensionWorlds.getOrDefault(uniqueId, Map.of()));
            if (bukkitWorld == null && dimensions.isEmpty()) {
                // Not loaded on this server; drop any stale registrations just in case.
                this.loadedWorlds.remove(uniqueId);
                this.worldNameToIslandId.remove(uniqueId.toString());
                this.missingDimensions.remove(uniqueId);
                future.complete(null);
                return;
            }

            // A deleted island can never be saved, whatever the caller asked for: the storage row is
            // already gone, and writing it back would resurrect the island.
            boolean effectiveSave = save && !this.deleted.contains(uniqueId);

            // Bukkit refuses to unload a world that still holds players, which is the normal case for
            // a disband (typed while standing on the island). Move them out first, then send them on
            // to the fallback group — the local hop is what actually frees the world.
            List<World> worlds = new ArrayList<>();
            dimensions.values().forEach(instance -> worlds.add(instance.getBukkitWorld()));
            if (bukkitWorld != null)
                worlds.add(bukkitWorld);
            worlds.forEach(this::evacuate);
            // Checked before anything is unloaded: stopping half-way would leave a dimension
            // behind an unloaded overworld, or the reverse.
            if (worlds.stream().anyMatch(world -> !world.getPlayers().isEmpty())) {
                future.completeExceptionally(new IllegalStateException("Players are still on island " + uniqueId + "; not unloading it"));
                return;
            }

            // Snapshot now, write later: serialising and writing the world on the main thread stalls
            // the tick. Bukkit itself is told not to save.
            List<CompletableFuture<Void>> saves = new ArrayList<>();
            for (Map.Entry<String, SlimeWorldInstance> entry : dimensions.entrySet()) {
                SlimeWorldInstance instance = entry.getValue();
                SlimeWorld snapshot = effectiveSave ? instance.getSerializableCopy() : null;
                if (!Bukkit.unloadWorld(instance.getBukkitWorld(), false)) {
                    // The overworld stays, so the island stays hosted here; what was already
                    // unloaded is saved below and comes back the next time someone enters it.
                    this.missingDimensions.computeIfAbsent(uniqueId, ignored -> ConcurrentHashMap.newKeySet())
                            .addAll(dimensions.keySet().stream().filter(dimension -> !this.dimensionWorlds
                                    .getOrDefault(uniqueId, Map.of()).containsKey(dimension)).toList());
                    future.completeExceptionally(new IllegalStateException("Bukkit refused to unload the " + entry.getKey()
                                                                           + " world of island " + uniqueId));
                    return;
                }
                unregisterDimension(uniqueId, entry.getKey(), instance);
                if (snapshot != null)
                    saves.add(saveAsync(uniqueId, snapshot));
            }

            if (bukkitWorld != null) {
                SlimeWorldInstance instance = this.loadedWorlds.get(uniqueId);
                SlimeWorld snapshot = effectiveSave && instance != null ? instance.getSerializableCopy() : null;

                // Unload world
                if (!Bukkit.unloadWorld(bukkitWorld, false)) {
                    if (!dimensions.isEmpty())
                        this.missingDimensions.computeIfAbsent(uniqueId, ignored -> ConcurrentHashMap.newKeySet()).addAll(dimensions.keySet());
                    future.completeExceptionally(new IllegalStateException("Bukkit refused to unload world for island with UUID: " + uniqueId));
                    return;
                }
                if (snapshot != null)
                    saves.add(saveAsync(uniqueId, snapshot));
            }

            // Remove from loaded worlds and world name mapping
            this.loadedWorlds.remove(uniqueId);
            this.worldNameToIslandId.remove(uniqueId.toString());
            this.missingDimensions.remove(uniqueId);

            // The island is only handed back once its saves have landed: another server picking it
            // up earlier would load the state from before this session.
            this.plugin.servers().refreshHeartbeat();
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                    .handle((ignored, saveError) -> this.plugin.servers()
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
            if (island != null && bukkitWorld != null)
                new IslandWorldUnloadedEvent(island, bukkitWorld).callEvent();
            else if (island == null)
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
                .thenCompose(ignored -> pendingSaves(uniqueId))
                .thenRunAsync(() -> deleteStoredWorlds(uniqueId), this.saveExecutor); // JDBC: not on the shared ForkJoin pool
    }

    /**
     * Deletes every stored world of the island: its overworld and each dimension found in storage
     * — not only the ones configured now, so a dimension since removed from the configuration is
     * not orphaned. Off the main thread.
     */
    private void deleteStoredWorlds(UUID uniqueId) {
        List<String> names = new ArrayList<>();
        names.add(uniqueId.toString());
        try {
            this.worldLoader.listWorlds().stream()
                    .filter(name -> IslandWorlds.belongsTo(name, uniqueId) && !name.equals(uniqueId.toString()))
                    .forEach(names::add);
        } catch (IOException e) {
            // Fall back on the configured dimensions; anything else is left for a manual cleanup.
            this.plugin.getSLF4JLogger().error("Failed to list the stored worlds of island {}; deleting its configured dimensions only", uniqueId, e);
            this.plugin.dimensions().all().forEach(dimension -> names.add(IslandWorlds.worldName(uniqueId, dimension.id())));
        }

        Exception failure = null;
        for (String name : names) {
            try {
                this.worldLoader.deleteWorld(name);
            } catch (UnknownWorldException e) {
                // Nothing persisted (e.g. a creation that failed before the clone) — treat as deleted.
                this.plugin.getSLF4JLogger().debug("World {} of island {} was not present in storage on delete", name, uniqueId);
            } catch (Exception e) {
                this.plugin.getSLF4JLogger().error("Failed to delete world {} of island {}", name, uniqueId, e);
                failure = e;
            }
        }
        if (failure != null)
            throw new CompletionException("Failed to delete every world of island with UUID: " + uniqueId, failure);
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
     * Runs on the main thread every {@link #IDLE_SWEEP_INTERVAL_TICKS} ticks: unloads any island that
     * has had no players in any of its worlds for at least the configured grace period, freeing memory
     * and the server's island slot. An island that regains a player before the grace elapses is spared
     * and its timer reset.
     */
    private void sweepIdleWorlds() {
        // A deleted island whose unload was refused earlier (players still inside) is retried on
        // every sweep, regardless of the idle settings — it is not idleness that must free it.
        for (UUID id : List.copyOf(this.deleted)) {
            if (!this.loadedWorlds.containsKey(id) && !this.dimensionWorlds.containsKey(id))
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

        for (UUID id : List.copyOf(this.loadedWorlds.keySet())) {
            if (this.deleted.contains(id))
                continue; // handled above

            if (!isEmpty(id)) {
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

    /** Whether nobody is in any of the island's worlds loaded here. Main thread. */
    private boolean isEmpty(UUID id) {
        for (SlimeWorldInstance instance : islandInstances(id)) {
            World world = instance.getBukkitWorld();
            if (world != null && !world.getPlayers().isEmpty())
                return false;
        }
        return true;
    }

    /**
     * Unloads an idle island, unless a player turned up after the sweep looked or is being sent
     * here right now — the host mapping still points at this server, so another server may be
     * transferring someone in, and unloading under them would leave them nowhere to land.
     */
    private void unloadIfStillIdle(UUID id, long idleMillis) {
        this.plugin.servers()
                .isArriving(id)
                .exceptionally(throwable -> false)
                .thenAccept(arriving -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (!this.loadedWorlds.containsKey(id) || this.deleted.contains(id))
                        return;
                    if (arriving || !isEmpty(id)) {
                        this.emptySince.remove(id); // start the idle clock over
                        return;
                    }

                    this.emptySince.remove(id);
                    this.plugin.getSLF4JLogger().info("Unloading idle island {} (empty for {}s)", id, idleMillis / 1000);
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

    /** The dimension this island world is; empty for an island's overworld, and for any other world. */
    public Optional<String> findDimension(World world) {
        return Optional.ofNullable(this.worldNameToDimension.get(world.getName()));
    }

    /** Whether {@code world} is one of this island's worlds — overworld or dimension — loaded here. */
    public boolean isIslandWorld(World world, UUID islandId) {
        return islandId.equals(this.worldNameToIslandId.get(world.getName()));
    }

    /** The island's overworld, when hosted here. */
    public Optional<SlimeWorldInstance> findByIslandId(UUID uniqueId) {
        return Optional.ofNullable(loadedWorlds.get(uniqueId));
    }

    /** One of the island's dimension worlds, when loaded here. */
    public Optional<World> findDimensionWorld(UUID islandId, String dimension) {
        SlimeWorldInstance instance = dimensionInstance(islandId, dimension);
        return instance == null ? Optional.empty() : Optional.ofNullable(instance.getBukkitWorld());
    }

    private SlimeWorldInstance dimensionInstance(UUID islandId, String dimension) {
        Map<String, SlimeWorldInstance> dimensions = this.dimensionWorlds.get(islandId);
        return dimensions == null ? null : dimensions.get(dimension);
    }

    /** The island's dimension worlds loaded here, by dimension id. */
    @Unmodifiable
    public Map<String, World> dimensionWorlds(UUID islandId) {
        Map<String, World> worlds = new LinkedHashMap<>();
        this.dimensionWorlds.getOrDefault(islandId, Map.of())
                .forEach((dimension, instance) -> worlds.put(dimension, instance.getBukkitWorld()));
        return worlds;
    }

    /** Every world of the island loaded here, its overworld first. */
    @Unmodifiable
    public List<SlimeWorldInstance> islandInstances(UUID islandId) {
        List<SlimeWorldInstance> instances = new ArrayList<>();
        SlimeWorldInstance overworld = this.loadedWorlds.get(islandId);
        if (overworld == null)
            return instances;
        instances.add(overworld);
        instances.addAll(this.dimensionWorlds.getOrDefault(islandId, Map.of()).values());
        return instances;
    }

    /** Every world of the island loaded here, its overworld first. */
    @Unmodifiable
    public List<World> islandWorlds(UUID islandId) {
        return islandInstances(islandId).stream().map(SlimeWorldInstance::getBukkitWorld).toList();
    }

    /**
     * Whether every world the island has in storage is loaded here. When one is not, its blocks are
     * out of sight and a level scan would drop them from the score.
     */
    public boolean isFullyLoaded(UUID islandId) {
        Set<String> missing = this.missingDimensions.get(islandId);
        return this.loadedWorlds.containsKey(islandId) && (missing == null || missing.isEmpty());
    }

    /** The overworlds hosted here, one per island. */
    @Unmodifiable
    public Map<UUID, SlimeWorldInstance> getLoadedWorlds() {
        return Map.copyOf(loadedWorlds);
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }

    /** An island's worlds as read from storage, before they are loaded. */
    private record StoredIsland(SlimeWorld overworld, Map<String, SlimeWorld> dimensions) {
    }
}
