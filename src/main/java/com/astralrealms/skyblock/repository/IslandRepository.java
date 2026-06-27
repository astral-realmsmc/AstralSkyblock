package com.astralrealms.skyblock.repository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.Island;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

public class IslandRepository extends UUIDSyncedRepository<Island> {

    private final Multimap<UUID, UUID> playerIslandMap;
    private final Map<UUID, UUID> worldIslandMap;

    public IslandRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ISLAND_CACHE_KEY,
                ASConstants.ISLAND_UPDATE_CHANNEL,
                cacheLoader -> Caffeine.newBuilder()
                        .maximumSize(250_000)
                        // TODO: Invalidation of indexes
                        .buildAsync(cacheLoader),
                Island.class
        );
        this.playerIslandMap = Multimaps.synchronizedMultimap(HashMultimap.create());
        this.worldIslandMap = new ConcurrentHashMap<>();
    }

    public CompletableFuture<Island> create(Player player) {
        return CompletableFuture.completedFuture(null);
    }


}
