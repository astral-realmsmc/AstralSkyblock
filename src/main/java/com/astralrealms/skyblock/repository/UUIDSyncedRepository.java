package com.astralrealms.skyblock.repository;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.astralrealms.core.model.Unique;
import com.astralrealms.core.storage.repository.CrudRepository;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.UniqueObjectUpdatePacket;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;

public abstract class UUIDSyncedRepository<V extends Unique> extends SyncedRepository<UUID, V> {

    private final CrudRepository<V> repository;

    public UUIDSyncedRepository(AstralSkyblock plugin, String cacheKey, String exchangeChannel, Function<AsyncCacheLoader<UUID, V>, AsyncLoadingCache<UUID, V>> cacheBuilder, Class<V> valueClass) {
        super(plugin, cacheKey, exchangeChannel, cacheBuilder, valueClass);
        this.repository = new CrudRepository<>(plugin.database(), valueClass);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof UniqueObjectUpdatePacket updatePacket)
                cache.synchronous().refresh(updatePacket.uniqueId());
            else if (packet instanceof UniqueObjectDeletePacket deletePacket)
                invalidateLocally(deletePacket.uniqueId());
        });
    }

    @Override
    protected UUID keyFromValue(V value) {
        return value.uniqueId();
    }

    @Override
    protected String cacheKey(UUID key) {
        return this.cacheKey + ":" + key.toString();
    }

    @Override
    protected CompletableFuture<V> loadById(UUID key) {
        return this.repository.findById(key)
                .thenApply(v -> v.orElse(null));
    }

    @Override
    protected CompletableFuture<V> saveToDatabase(V value) {
        return this.repository.save(value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(UUID key) {
        return this.repository.delete(key);
    }

    @Override
    protected void publishUpdate(UUID key, V value) {
        this.plugin.messaging().send(exchangeChannel, new UniqueObjectUpdatePacket(key));
    }

    @Override
    protected void publishInvalidation(UUID key) {
        this.plugin.messaging().send(exchangeChannel, new UniqueObjectDeletePacket(key));
    }
}
