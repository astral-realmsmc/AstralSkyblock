package com.astralrealms.skyblock.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Idle-unload sweep cadence, in ticks (30s at 20 TPS). */
    private static final long IDLE_SWEEP_INTERVAL_TICKS = 20L * 30;

    private final AstralSkyblock plugin;
    private final AdvancedSlimePaperAPI asp = AdvancedSlimePaperAPI.instance();
    private final Map<UUID, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();
    private final Map<String, UUID> worldNameToIslandId = new ConcurrentHashMap<>();

    // Coalesces concurrent load() calls for the same island so a world is never loaded twice.
    private final Map<UUID, CompletableFuture<SlimeWorldInstance>> loading = new ConcurrentHashMap<>();
    // First tick (millis) a loaded world was observed empty; cleared as soon as a player is present.
    private final Map<UUID, Long> emptySince = new ConcurrentHashMap<>();

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
        List<CompletableFuture<Void>> hostCleanups = new ArrayList<>();
        for (SlimeWorldInstance instance : loadedWorlds.values()) {
            try {
                asp.saveWorld(instance);
            } catch (IOException e) {
                this.plugin.getSLF4JLogger().error("Failed to save world: {}", instance.getName(), e);
            }

            UUID uniqueId = UUID.fromString(instance.getName());
            hostCleanups.add(this.plugin.servers()
                    .deleteHostServer(uniqueId)
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to delete host server for island with UUID: {}", uniqueId, throwable);
                        return null;
                    }));

            this.plugin.getSLF4JLogger().info("World {} saved successfully.", instance.getName());
        }

        try {
            CompletableFuture.allOf(hostCleanups.toArray(CompletableFuture[]::new))
                    .get(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().warn("Timed out flushing host-server cleanup on shutdown", e);
        }

        this.loadedWorlds.clear();
        this.worldNameToIslandId.clear();
        this.emptySince.clear();
        this.loading.clear();

        // Close loader
        if (this.worldLoader != null) {
            this.worldLoader.close();
            this.worldLoader = null;
        }
    }

    public CompletableFuture<SlimeWorldInstance> create(UUID uniqueId, IslandBlueprint blueprint) {
        return this.createNewWorld(uniqueId, blueprint)
                .thenCompose(clonedWorld -> this.loadWorld(uniqueId, clonedWorld))
                .thenApply(instance -> {
                    try {
                        asp.saveWorld(instance);
                    } catch (IOException e) {
                        throw new CompletionException("Failed to save world after creation for island with UUID: " + uniqueId, e);
                    }

                    return instance;
                })
                .exceptionallyCompose(throwable -> {
                    // Best-effort teardown of whatever got created so a failed creation never leaves an
                    // orphaned slime world (or a half-loaded Bukkit world) behind.
                    this.plugin.getSLF4JLogger().error("Creation failed for island {}; cleaning up partial world", uniqueId, throwable);
                    return this.delete(uniqueId)
                            .exceptionally(cleanupError -> {
                                this.plugin.getSLF4JLogger().error("Failed to clean up partial world for island {}", uniqueId, cleanupError);
                                return null;
                            })
                            .thenCompose(ignored -> CompletableFuture.failedFuture(unwrap(throwable)));
                });
    }

    public CompletableFuture<SlimeWorldInstance> load(Island island) {
        UUID id = island.uniqueId();

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

        CompletableFuture<SlimeWorld> read = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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
        });

        read.thenCompose(world -> this.loadWorld(id, world))
                .whenComplete((instance, throwable) -> {
                    this.loading.remove(id);
                    if (throwable != null)
                        inFlight.completeExceptionally(throwable);
                    else
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
                // Register the loaded world instance
                this.loadedWorlds.put(id, instance);
                this.worldNameToIslandId.put(instance.getName(), id);

                // Apply environment settings to the world based on the island's settings
                Island island = this.plugin.islands()
                        .repository()
                        .findCachedById(id)
                        .orElseThrow(() -> new IllegalStateException("Island not found for UUID: " + id));
                IslandSettingsListener.applyEnvironment(island, instance.getBukkitWorld());

                // Set host server for this island
                this.plugin.servers()
                        .setHostServer(id, AstralPaperAPI.serverInformation().uniqueId())
                        .exceptionally(throwable -> {
                            plugin.getSLF4JLogger().error("Failed to set host server for island with UUID: {}", id, throwable);
                            return null;
                        });

                // Throw event
                new IslandWorldLoadedEvent(island, instance.getBukkitWorld()).callEvent();

                future.complete(instance);
            } catch (Exception ex) {
                // Roll back the partially-registered world so a post-load failure can't leak a world that
                // stays resident in Bukkit and the maps while the caller sees a failure.
                this.loadedWorlds.remove(id);
                this.worldNameToIslandId.remove(instance.getName());
                try {
                    Bukkit.unloadWorld(instance.getBukkitWorld(), false);
                } catch (Exception unloadError) {
                    this.plugin.getSLF4JLogger().error("Failed to roll back partially loaded world for island {}", id, unloadError);
                }
                future.completeExceptionally(ex);
            }
        });
        return future;
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

    public CompletableFuture<Void> save(UUID uniqueId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SlimeWorldInstance instance = this.loadedWorlds.get(uniqueId);
            if (instance == null) {
                future.completeExceptionally(new IllegalStateException("World instance not loaded for island with UUID: " + uniqueId));
                return;
            }

            try {
                asp.saveWorld(instance);
                future.complete(null);
            } catch (IOException e) {
                future.completeExceptionally(new CompletionException("Failed to save world for island with UUID: " + uniqueId, e));
            }
        });
        return future;
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

            // Bukkit refuses to unload a world that still holds players, which is the normal case for
            // a disband (typed while standing on the island). Move them out first, then send them on
            // to the fallback group — the local hop is what actually frees the world.
            evacuate(bukkitWorld);

            // Unload world
            boolean success = Bukkit.unloadWorld(bukkitWorld, save);
            if (!success) {
                future.completeExceptionally(new IllegalStateException("Bukkit refused to unload world for island with UUID: " + uniqueId));
                return;
            }

            // Remove from loaded worlds and world name mapping
            this.loadedWorlds.remove(uniqueId);
            this.worldNameToIslandId.remove(uniqueId.toString());
            this.plugin.servers()
                    .deleteHostServer(uniqueId)
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

    public CompletableFuture<Void> delete(UUID uniqueId) {
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
                        this.plugin.getSLF4JLogger().error("Failed to unload world for island {} before deleting it; "
                                                           + "deleting it from storage anyway", uniqueId, throwable);
                    return null;
                })
                .thenCompose(ignored -> this.plugin.servers().deleteHostServer(uniqueId))
                .thenRunAsync(() -> {
                    try {
                        this.worldLoader.deleteWorld(uniqueId.toString());
                    } catch (UnknownWorldException e) {
                        // Nothing persisted (e.g. a creation that failed before the clone) — treat as deleted.
                        this.plugin.getSLF4JLogger().debug("World for island {} was not present in storage on delete", uniqueId);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to delete world for island with UUID: " + uniqueId, e);
                    }
                });
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

        World fallback = Bukkit.getWorlds().stream()
                .filter(candidate -> !candidate.equals(world))
                .findFirst()
                .orElse(null);
        this.plugin.getSLF4JLogger().info("Evacuating {} player(s) from island world {}", players.size(), world.getName());

        for (Player player : players) {
            if (fallback != null)
                player.teleport(fallback.getSpawnLocation());

            AstralPaperAPI.getService(TeleportationService.class)
                    .orElseThrow()
                    .sendToGroup(player.getUniqueId(), this.plugin.configuration().fallbackGroup())
                    .exceptionally(throwable -> {
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
        int idleSeconds = this.plugin.configuration().worldIdleUnloadSeconds();
        if (idleSeconds < 0)
            return; // idle unloading disabled

        long now = System.currentTimeMillis();
        long graceMillis = idleSeconds * 1000L;

        for (Map.Entry<UUID, SlimeWorldInstance> entry : this.loadedWorlds.entrySet()) {
            UUID id = entry.getKey();
            World world = entry.getValue().getBukkitWorld();
            if (world == null || !world.getPlayers().isEmpty()) {
                this.emptySince.remove(id);
                continue;
            }

            Long since = this.emptySince.putIfAbsent(id, now);
            if (since == null)
                continue; // first sweep seeing it empty — start the clock

            if (now - since >= graceMillis) {
                this.emptySince.remove(id);
                this.plugin.getSLF4JLogger().info("Unloading idle island world {} (empty for {}s)", id, (now - since) / 1000);
                this.unload(id).exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to unload idle island world {}", id, throwable);
                    return null;
                });
            }
        }
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
