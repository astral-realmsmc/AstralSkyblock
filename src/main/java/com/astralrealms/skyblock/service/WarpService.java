package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.repository.WarpRepository;

import lombok.Getter;

@Getter
public class WarpService {

    private final AstralSkyblock plugin;
    private final WarpRepository repository;

    public WarpService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new WarpRepository(plugin);
    }

    /** The island's warps from the local cache (empty if the island is not active here). */
    @Unmodifiable
    public Collection<IslandWarp> warps(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandWarp> set(IslandWarp warp) {
        return this.repository.set(warp);
    }

    public CompletableFuture<IslandWarp> remove(UUID islandId, String name) {
        return this.repository.remove(islandId, name);
    }
}
