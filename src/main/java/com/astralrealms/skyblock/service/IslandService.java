package com.astralrealms.skyblock.service;

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

    }
}
