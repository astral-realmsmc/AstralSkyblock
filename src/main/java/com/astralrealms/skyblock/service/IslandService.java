package com.astralrealms.skyblock.service;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.repository.IslandRepository;

public class IslandService {

    private final AstralSkyblock plugin;
    private final IslandRepository repository;

    public IslandService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new IslandRepository(plugin);
    }

    public void create(Player player, IslandBlueprint blueprint) {
        long startTime = System.currentTimeMillis();
        UUID islandId = UUID.randomUUID();
        this.plugin.worlds().create(islandId, blueprint)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}", player.getName(), throwable);
                        return;
                    } else if (result == null) {
                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}: result is null", player.getName());
                        return;
                    }

                    this.plugin.getSLF4JLogger().info("Island created for player {} in {} ms", islandId, System.currentTimeMillis() - startTime);
                    player.teleportAsync(result.getBukkitWorld().getSpawnLocation());
                });
    }
}
