package com.astralrealms.skyblock.repository;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

/**
 * A {@link SyncedRepository} that additionally keeps a single secondary index mapping an index key
 * {@code I} (here always an island id) to the cache keys {@code K} of the values that belong to it.
 *
 * <p>The index is maintained on <b>every</b> path that inserts a value into L1 — the async loader
 * (read-through and {@code refresh()}), the write-through {@link #cacheLocally(Object)}, and the
 * bulk {@link #prime(Object)} — and is cleared on eviction and {@link #invalidateLocally(Object)}.
 * This is the key property the relationship accessors rely on: an active island's slice is complete
 * after {@link #prime(Object)} and stays correct as individual entries load, refresh, or invalidate.
 */
public abstract class IndexedSyncedRepository<K, V, I> extends SyncedRepository<K, V> {

    private final Multimap<I, K> index = Multimaps.synchronizedMultimap(HashMultimap.create());

    public IndexedSyncedRepository(AstralSkyblock plugin, String cacheKey, String exchangeChannel, Class<V> valueClass) {
        super(plugin, cacheKey, exchangeChannel, valueClass);
    }

    /** Extracts the index key (island id) from a value. */
    protected abstract I indexKeyOf(V value);

    /** Bulk-loads every value under an index key from the database. */
    protected abstract CompletableFuture<List<V>> loadByIndex(I indexKey);

    @Override
    protected AsyncLoadingCache<K, V> buildCache(AsyncCacheLoader<K, V> cacheLoader) {
        return Caffeine.newBuilder()
                .recordStats()
                .evictionListener((RemovalListener<K, V>) (key, value, _) -> {
                    if (key != null)
                        deindex(key, value);
                })
                // Wrap the loader so a value entering L1 via read-through or refresh() is indexed too.
                .buildAsync((key, executor) -> cacheLoader.asyncLoad(key, executor)
                        .thenApply(value -> {
                            if (value != null)
                                index(value);
                            return value;
                        }));
    }

    @Override
    protected void cacheLocally(V value) {
        super.cacheLocally(value);
        index(value);
    }

    @Override
    public @Nullable V invalidateLocally(K key) {
        V value = super.invalidateLocally(key);
        deindex(key, value);
        return value;
    }

    /** Adds {@code value}'s key to its index slice. Idempotent. */
    protected void index(V value) {
        this.index.put(indexKeyOf(value), keyFromValue(value));
    }

    /** Removes {@code key} from {@code value}'s slice (best-effort if {@code value} is null). */
    protected void deindex(K key, @Nullable V value) {
        if (value != null)
            this.index.remove(indexKeyOf(value), key);
        else
            this.index.values().remove(key);
    }

    /** Snapshot of the cache keys currently indexed under {@code indexKey}. */
    @Unmodifiable
    public Collection<K> keysIn(I indexKey) {
        return List.copyOf(this.index.get(indexKey));
    }

    /**
     * Bulk-loads every value under {@code indexKey}, atomically replacing that index slice and
     * populating L1. This is the lifecycle prime primitive; it does not write to L2 (Redis).
     */
    public CompletableFuture<List<V>> prime(I indexKey) {
        return loadByIndex(indexKey)
                .thenApply(values -> {
                    this.index.replaceValues(indexKey, values.stream().map(this::keyFromValue).toList());
                    values.forEach(value -> cache.synchronous().put(keyFromValue(value), value));
                    onPrimed(indexKey, values);
                    return values;
                });
    }

    /**
     * Hook for subclasses maintaining additional indexes (e.g. a player→island index) to react to a
     * completed prime. Default: no-op. Called after the primary slice and L1 are populated.
     */
    protected void onPrimed(I indexKey, List<V> values) {
    }

    /**
     * Drops every value under {@code indexKey} from this server's L1 cache (L2 stays warm) and clears
     * the slice. Used when an island deactivates locally.
     */
    public void evictIndex(I indexKey) {
        List.copyOf(this.index.get(indexKey)).forEach(this::invalidateLocally);
        this.index.removeAll(indexKey);
    }
}
