package com.astralrealms.skyblock.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
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

    private final AstralSkyblock plugin;
    private final AdvancedSlimePaperAPI asp = AdvancedSlimePaperAPI.instance();
    private final Map<UUID, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();

    private final FileLoader sourceLoader;
    private MysqlLoader worldLoader;

    public WorldService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.sourceLoader = new FileLoader(plugin.getDataPath().resolve("sourceWorlds").toFile());
    }

    public void load() {
        this.plugin.getSLF4JLogger().info("Initializing mysql asp loader...");

        // Close previous dataSource
        this.unload();

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
    }

    public void unload() {
        // Save all worlds sync
        for (SlimeWorldInstance instance : loadedWorlds.values()) {
            // Save world
            try {
                asp.saveWorld(instance);
            } catch (IOException e) {
                this.plugin.getSLF4JLogger().error("Failed to save world: {}", instance.getName(), e);
            }

            // Delete host server
            UUID uniqueId = UUID.fromString(instance.getName());
            this.plugin.servers()
                    .deleteHostServer(uniqueId)
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to delete host server for island with UUID: {}", uniqueId, throwable);
                        return null;
                    });

            this.plugin.getSLF4JLogger().info("World {} saved and unloaded successfully.", instance.getName());
        }

        // Close loader
        if (this.worldLoader != null)
            this.worldLoader.close();
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
                });
    }

    public CompletableFuture<SlimeWorldInstance> load(Island island) {
        CompletableFuture<SlimeWorld> read = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SlimePropertyMap propertyMap = buildPropertyMap(
                        (int) island.spawnX(),
                        (int) island.spawnY(),
                        (int) island.spawnZ(),
                        island.spawnYaw()
                );
                read.complete(asp.readWorld(this.worldLoader, island.uniqueId().toString(), false, propertyMap));
            } catch (UnknownWorldException | IOException | CorruptedWorldException | NewerFormatException e) {
                read.completeExceptionally(e);
            }
        });
        return read.thenCompose(world -> this.loadWorld(island.uniqueId(), world));
    }

    private CompletableFuture<SlimeWorldInstance> loadWorld(UUID id, SlimeWorld world) {
        CompletableFuture<SlimeWorldInstance> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                SlimeWorldInstance instance = asp.loadWorld(world, true);
                this.loadedWorlds.put(id, instance);
                this.plugin.servers()
                        .setHostServer(id, AstralPaperAPI.serverInformation().uniqueId())
                        .exceptionally(throwable -> {
                            plugin.getSLF4JLogger().error("Failed to set host server for island with UUID: {}", id, throwable);
                            return null;
                        });
                future.complete(instance);
            } catch (Exception ex) {
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
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            World bukkitWorld = Bukkit.getWorld(uniqueId.toString());
            if (bukkitWorld == null) {
                future.complete(null);
                return;
            }

            boolean success = Bukkit.unloadWorld(bukkitWorld, true);
            if (!success) {
                future.completeExceptionally(new IllegalStateException("Bukkit refused to unload world for island with UUID: " + uniqueId));
                return;
            }

            this.loadedWorlds.remove(uniqueId);
            this.plugin.servers()
                    .deleteHostServer(uniqueId)
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to delete host server for island with UUID: {}", uniqueId, throwable);
                        return null;
                    });
            future.complete(null);
        });
        return future;
    }

    public CompletableFuture<Void> delete(UUID uniqueId) {
        return this.unload(uniqueId)
                .thenRunAsync(() -> {
                    try {
                        this.worldLoader.deleteWorld(uniqueId.toString());
                    } catch (Exception e) {
                        throw new CompletionException("Failed to delete world for island with UUID: " + uniqueId, e);
                    }
                });
    }

    public Optional<SlimeWorldInstance> findByIslandId(UUID uniqueId) {
        return Optional.ofNullable(loadedWorlds.get(uniqueId));
    }

    @Unmodifiable
    public Map<UUID, SlimeWorldInstance> getLoadedWorlds() {
        return Map.copyOf(loadedWorlds);
    }
}
