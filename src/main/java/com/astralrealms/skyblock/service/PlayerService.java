package com.astralrealms.skyblock.service;

import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.repository.PlayerRepository;

public class PlayerService {

    private final AstralSkyblock plugin;
    private final PlayerRepository repository;

    public PlayerService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new PlayerRepository(plugin);
    }

    public void load(Player player) {
        this.repository.recordSeen(player.getUniqueId(), player.getName())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to record player {} as seen", player.getName(), throwable);
                    return null;
                });
    }

}
