package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.repository.FlagRepository;

import lombok.Getter;

@Getter
public class FlagService {

    private final AstralSkyblock plugin;
    private final FlagRepository repository;

    public FlagService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new FlagRepository(plugin);
    }

    /** The island's flags from the local cache (empty if the island is not active here). */
    @Unmodifiable
    public Collection<IslandFlag> flags(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandFlag> set(UUID islandId, String flag, boolean allowed) {
        return this.repository.set(islandId, flag, allowed);
    }

    public CompletableFuture<IslandFlag> remove(UUID islandId, String flag) {
        return this.repository.remove(islandId, flag);
    }
}
