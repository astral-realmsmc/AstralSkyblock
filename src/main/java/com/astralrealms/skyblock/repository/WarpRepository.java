package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island warps, keyed by the composite {@link WarpKey} (island + name). */
public class WarpRepository extends IndexedSyncedRepository<WarpKey, IslandWarp, UUID> {

    private static final String COLUMNS = "island_id, name, x, y, z, yaw, pitch, is_private, created_at";

    public WarpRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.WARP_CACHE_KEY, ASConstants.WARP_UPDATE_CHANNEL, IslandWarp.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new WarpKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new WarpKey(delete.islandId(), delete.key()));
        });
    }

    /** All warps of an island. Primes the per-warp cache and index. */
    public CompletableFuture<List<IslandWarp>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /** Creates or updates a warp. */
    public CompletableFuture<IslandWarp> set(IslandWarp warp) {
        return save(warp);
    }

    /** Removes a warp. */
    public CompletableFuture<IslandWarp> remove(UUID islandId, String name) {
        return delete(new WarpKey(islandId, name));
    }

    @Override
    protected UUID indexKeyOf(IslandWarp value) {
        return value.islandId();
    }

    @Override
    protected WarpKey keyFromValue(IslandWarp value) {
        return new WarpKey(value.islandId(), value.name());
    }

    @Override
    protected String cacheKey(WarpKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.name();
    }

    @Override
    protected CompletableFuture<IslandWarp> loadById(WarpKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ? AND name = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandWarp>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandWarp> warps = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        warps.add(map(resultSet));
                }
            }
            return warps;
        });
    }

    @Override
    protected CompletableFuture<IslandWarp> saveToDatabase(IslandWarp value) {
        String query = "INSERT INTO island_warps (island_id, name, x, y, z, yaw, pitch, is_private) "
                       + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE x = VALUES(x), y = VALUES(y), z = VALUES(z), "
                       + "yaw = VALUES(yaw), pitch = VALUES(pitch), is_private = VALUES(is_private)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.name());
                statement.setDouble(3, value.x());
                statement.setDouble(4, value.y());
                statement.setDouble(5, value.z());
                statement.setFloat(6, value.yaw());
                statement.setFloat(7, value.pitch());
                statement.setBoolean(8, value.isPrivate());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(WarpKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM island_warps WHERE island_id = ? AND name = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.name());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(WarpKey key, IslandWarp value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.name()));
    }

    @Override
    protected void publishInvalidation(WarpKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.name()));
    }

    private IslandWarp map(ResultSet resultSet) throws SQLException {
        return new IslandWarp(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                resultSet.getFloat("yaw"),
                resultSet.getFloat("pitch"),
                resultSet.getBoolean("is_private"),
                resultSet.getTimestamp("created_at") == null ? 0L : resultSet.getTimestamp("created_at").getTime()
        );
    }
}
