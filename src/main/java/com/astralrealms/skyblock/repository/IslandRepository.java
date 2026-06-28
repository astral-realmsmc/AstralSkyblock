package com.astralrealms.skyblock.repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.core.storage.pagination.Pageable;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

public class IslandRepository extends UUIDSyncedRepository<Island> {

    private final Map<String, UUID> nameIslandMap;
    private final Multimap<UUID, UUID> playerIslandMap;
    private final Map<UUID, UUID> worldIslandMap;

    public IslandRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ISLAND_CACHE_KEY,
                ASConstants.ISLAND_UPDATE_CHANNEL,
                cacheLoader -> Caffeine.newBuilder()
                        .maximumSize(250_000)
                        .buildAsync(cacheLoader),
                Island.class
        );
        this.playerIslandMap = Multimaps.synchronizedMultimap(HashMultimap.create());
        this.worldIslandMap = new ConcurrentHashMap<>();
        this.nameIslandMap = new ConcurrentHashMap<>();
    }


    @Override
    protected void cacheLocally(Island value) {
        super.cacheLocally(value);
        if (value.name() != null)
            this.nameIslandMap.put(value.name(), value.uniqueId());
    }

    /**
     * Loads every island into the local L1 cache (and name index) in pages of
     * {@link ASConstants#ISLAND_WARMUP_PAGE_SIZE}, one page at a time, so a large island table never
     * has to be held in a single result set. Intended to be called once on startup.
     */
    public CompletableFuture<Void> warmup() {
        return warmupPage(0);
    }

    private CompletableFuture<Void> warmupPage(int page) {
        Pageable pageable = Pageable.of(page, ASConstants.ISLAND_WARMUP_PAGE_SIZE, "id");
        return this.repository.findAll(pageable)
                .thenCompose(result -> {
                    result.content().forEach(this::cacheLocally);
                    if (result.hasNext())
                        return warmupPage(page + 1);
                    return CompletableFuture.completedFuture(null);
                });
    }

    public Optional<Island> findByName(String name) {
        UUID islandId = this.nameIslandMap.get(name);
        if (islandId == null)
            return Optional.empty();
        return findCachedById(islandId);
    }

    @Unmodifiable
    public Collection<String> names() {
        return this.nameIslandMap.keySet();
    }
}
