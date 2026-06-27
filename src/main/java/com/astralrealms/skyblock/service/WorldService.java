package com.astralrealms.skyblock.service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASPLoaderConfiguration;
import com.astralrealms.skyblock.model.IslandBlueprint;
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
        if (this.worldLoader != null)
            this.worldLoader.close();
    }

    public CompletableFuture<SlimeWorldInstance> create(UUID uniqueId, IslandBlueprint blueprint) {
        return this.createNewWorld(uniqueId, blueprint)
                .thenCompose(this::loadWorld)
                .thenCompose(slimeWorldInstance -> {
                    try {
                        asp.saveWorld(slimeWorldInstance);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to save world after copying schematic", e);
                    }
                    return CompletableFuture.completedFuture(slimeWorldInstance);
                });
    }

    private CompletableFuture<SlimeWorldInstance> loadWorld(SlimeWorld world) {
        CompletableFuture<SlimeWorldInstance> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                SlimeWorldInstance instance = asp.loadWorld(world, true);
                future.complete(instance);
            } catch (IllegalArgumentException ex) {
                future.completeExceptionally(ex);
            }
        });
        return future.exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to load world: {}", world.getName(), throwable);
            return null;
        });
    }

    private CompletableFuture<SlimeWorld> createNewWorld(UUID uniqueId, IslandBlueprint blueprint) {
        CompletableFuture<SlimeWorld> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Setup property map
            SlimePropertyMap propertyMap = new SlimePropertyMap();

            MinecraftLocation spawnLocation = blueprint.spawnLocation();
            propertyMap.setValue(SlimeProperties.SPAWN_X, (int) spawnLocation.x());
            propertyMap.setValue(SlimeProperties.SPAWN_Y, (int) spawnLocation.y());
            propertyMap.setValue(SlimeProperties.SPAWN_Z, (int) spawnLocation.z());
            propertyMap.setValue(SlimeProperties.SPAWN_YAW, spawnLocation.yaw());

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

            // Load source world
            SlimeWorld sourceWorld;
            try {
                sourceWorld = this.asp.readWorld(this.sourceLoader, blueprint.sourceWorld().replace(".slime", ""), false, propertyMap);
            } catch (UnknownWorldException | IOException | CorruptedWorldException | NewerFormatException e) {
                future.completeExceptionally(e);
                return;
            }

            // Clone source world
            try {
                SlimeWorld clonedWorld = sourceWorld.clone(uniqueId.toString(), this.worldLoader);
                future.complete(clonedWorld);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to create world for island with UUID: {}", uniqueId, throwable);
            return null;
        });
    }

}
