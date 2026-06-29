package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.repository.UpgradeRepository;

import lombok.Getter;

@Getter
public class UpgradeService {

    private final AstralSkyblock plugin;
    private final UpgradeRepository repository;

    public UpgradeService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new UpgradeRepository(plugin);
    }

    /** The island's upgrades from the local cache (empty if the island is not active here). */
    @Unmodifiable
    public Collection<IslandUpgrade> upgrades(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandUpgrade> setLevel(UUID islandId, String upgrade, int level) {
        return this.repository.setLevel(islandId, upgrade, level);
    }

    public CompletableFuture<IslandUpgrade> remove(UUID islandId, String upgrade) {
        return this.repository.remove(islandId, upgrade);
    }
}
