package com.astralrealms.skyblock.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.google.gson.Gson;

/**
 * A three-tier, cross-server cache backing a repository.
 *
 * <ul>
 *     <li><b>L1</b> – a local Caffeine cache, per server instance.</li>
 *     <li><b>L2</b> – a shared Redis cache, visible to every server instance.</li>
 *     <li><b>L3</b> – the backing database (the source of truth).</li>
 * </ul>
 *
 * Coherency across servers is maintained with Redis pub/sub: {@link #publishUpdate(Object, Object)}
 * and {@link #publishInvalidation(Object)} notify other instances, whose subscribers are expected to
 * call {@link #invalidateLocally(Object)} (L1 only — L2 is shared and already up to date).
 */
public abstract class SyncedRepository<K, V> {

    private static final Gson GSON = new Gson();

    protected final AstralSkyblock plugin;
    protected final String cacheKey;
    protected final String exchangeChannel;
    protected final AsyncLoadingCache<K, V> cache;
    protected final Class<V> valueClass;

    public SyncedRepository(AstralSkyblock plugin, String cacheKey, String exchangeChannel, Function<AsyncCacheLoader<K, V>, AsyncLoadingCache<K, V>> cacheBuilder, Class<V> valueClass) {
        this.plugin = plugin;
        this.cacheKey = cacheKey;
        this.exchangeChannel = exchangeChannel;
        this.valueClass = valueClass;
        this.cache = cacheBuilder.apply((key, _) -> load(key));
    }

    public Optional<V> findCachedById(K key) {
        return Optional.ofNullable(cache.synchronous().getIfPresent(key));
    }

    public CompletableFuture<V> findById(K key) {
        return cache.get(key);
    }

    public CompletableFuture<V> save(V value) {
        return saveToDatabase(value)
                .thenCompose(savedValue -> {
                    K key = keyFromValue(savedValue);
                    // Publish only once L2 reflects the write, so a server reacting to the update
                    // cannot read a stale value still sitting in the shared cache.
                    return cache(savedValue).thenApply(_ -> {
                        publishUpdate(key, savedValue);
                        return savedValue;
                    });
                });
    }

    public CompletableFuture<V> delete(K key) {
        return deleteFromDatabase(key)
                .thenApply(_ -> this.invalidateGlobally(key));
    }

    /**
     * Invalidates the entry on every server: drops it from the local L1 cache, removes it from the
     * shared L2 (Redis) cache, and notifies other instances to drop their own L1 copy.
     */
    public @Nullable V invalidateGlobally(K key) {
        V value = invalidateLocally(key);
        this.plugin.cache()
                .del(cacheKey(key))
                .exceptionally(throwable -> logCacheFailure("delete", key, throwable));
        publishInvalidation(key);
        return value;
    }

    /**
     * Invalidates the entry in this server's local L1 cache only. Intended for subscribers reacting
     * to a remote {@link #publishInvalidation(Object)} — the shared L2 cache is already up to date.
     */
    public @Nullable V invalidateLocally(K key) {
        V value = cache.synchronous().getIfPresent(key);
        cache.synchronous().invalidate(key);
        return value;
    }

    private CompletableFuture<V> load(K key) {
        return this.plugin.cache()
                .get(cacheKey(key))
                .thenCompose(cachedValue -> {
                    if (cachedValue != null)
                        return CompletableFuture.completedFuture(GSON.fromJson(cachedValue, valueClass));

                    // Miss in L2: fall through to the database and populate L2. L1 is populated
                    // automatically by the AsyncLoadingCache from the value returned here.
                    return loadById(key)
                            .thenApply(value -> {
                                if (value != null)
                                    writeToL2(key, value);
                                return value;
                            });
                })
                .toCompletableFuture();
    }

    /**
     * Writes the value through to L1 (synchronously) and L2 (asynchronously). The returned future
     * completes once the L2 write settles — successfully, or after a failure has been logged.
     */
    protected CompletableFuture<Void> cache(V value) {
        K key = keyFromValue(value);
        this.cache.synchronous().put(key, value);
        return writeToL2(key, value);
    }

    private CompletableFuture<Void> writeToL2(K key, V value) {
        String json = GSON.toJson(value);
        Duration ttl = cacheTtl();
        CompletableFuture<String> result = ttl == null
                ? this.plugin.cache().set(cacheKey(key), json)
                : this.plugin.cache().set(cacheKey(key), json, ttl);
        return result
                .exceptionally(throwable -> logCacheFailure("write", key, throwable))
                .thenAccept(_ -> {});
    }

    private <T> T logCacheFailure(String operation, K key, Throwable throwable) {
        this.plugin.platformAdapter()
                .logger()
                .error("Failed to {} '{}' in the Redis cache.", operation, cacheKey(key), throwable);
        return null;
    }

    /**
     * Time-to-live applied to L2 (Redis) entries, as defense-in-depth against a missed invalidation
     * (e.g. a server crashing before its invalidation is published). {@code null} means no expiry.
     */
    protected @Nullable Duration cacheTtl() {
        return null;
    }

    // Key
    protected abstract K keyFromValue(V value);

    protected abstract String cacheKey(K key);

    // Database
    protected abstract CompletableFuture<V> loadById(K key);

    protected abstract CompletableFuture<V> saveToDatabase(V value);

    protected abstract CompletableFuture<Void> deleteFromDatabase(K key);

    // Messaging
    protected abstract void publishUpdate(K key, V value);

    protected abstract void publishInvalidation(K key);
}
