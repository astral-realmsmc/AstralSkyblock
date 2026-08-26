package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Repository for island warps, keyed by the composite {@link WarpKey} (island + lower-cased name)
 * that mirrors the {@code island_warps} primary key.
 *
 * <p>Cross-server coherency flows through {@link IslandStringKeyUpdatePacket}/
 * {@link IslandStringKeyDeletePacket} on the warp channel; there is no shared L2 cache, so an
 * update refreshes the entry from the database.
 */
public class WarpRepository extends IndexedSyncedRepository<WarpKey, IslandWarp, UUID> {

    private static final String COLUMNS = "island_id, name, x, y, z, yaw, pitch, is_private, icon, display_name, description, created_at";

    public WarpRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.WARP_CACHE_KEY, ASConstants.WARP_UPDATE_CHANNEL, IslandWarp.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            // Packets can arrive before IslandService exists (this repository is built first).
            if (this.plugin.islands() == null)
                return;

            if (packet instanceof IslandStringKeyUpdatePacket update) {
                // The warp may be brand new here (created on another server); refresh both reloads a
                // cached warp and loads a missing one.
                cache.synchronous().refresh(new WarpKey(update.islandId(), update.key()))
                        .thenAccept(ignored -> this.plugin.islands().refreshWarps(update.islandId()));
            } else if (packet instanceof IslandStringKeyDeletePacket delete) {
                invalidateLocally(new WarpKey(delete.islandId(), delete.key()));
                this.plugin.islands().refreshWarps(delete.islandId());
            }
        });
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /** Every warp of an island. Primes the per-entry cache and the island's index slice. */
    public CompletableFuture<List<IslandWarp>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /** Cached warps of an island, without touching the database. */
    public List<IslandWarp> findCachedByIsland(UUID islandId) {
        return keysIn(islandId).stream()
                .map(key -> findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Reads a single warp through the cache, falling back to the database on a miss. */
    public CompletableFuture<Optional<IslandWarp>> findByName(UUID islandId, String name) {
        return findById(new WarpKey(islandId, name)).thenApply(Optional::ofNullable);
    }

    /** Upserts a warp, writes it through the local cache and publishes it to other servers. */
    public CompletableFuture<IslandWarp> upsert(IslandWarp warp) {
        return save(warp);
    }

    /** Deletes a warp, evicting it everywhere. */
    public CompletableFuture<Void> remove(UUID islandId, String name) {
        return delete(new WarpKey(islandId, name)).thenAccept(ignored -> {
        });
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // warps are local-cache + database only; cross-server sync via packets
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
        @Language("SQL") String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ? AND name = ?";
        return this.plugin.database()
                .supply(connection -> {
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
    protected CompletableFuture<IslandWarp> saveToDatabase(IslandWarp value) {
        @Language("SQL") String query = """
                INSERT INTO island_warps (island_id, name, x, y, z, yaw, pitch, is_private, icon, display_name, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE x            = VALUES(x),
                                        y            = VALUES(y),
                                        z            = VALUES(z),
                                        yaw          = VALUES(yaw),
                                        pitch        = VALUES(pitch),
                                        is_private   = VALUES(is_private),
                                        icon         = VALUES(icon),
                                        display_name = VALUES(display_name),
                                        description  = VALUES(description)
                """;
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, value.islandId());
                        statement.setString(2, value.name());
                        statement.setDouble(3, value.x());
                        statement.setDouble(4, value.y());
                        statement.setDouble(5, value.z());
                        statement.setFloat(6, value.yaw());
                        statement.setFloat(7, value.pitch());
                        statement.setBoolean(8, value.isPrivate());
                        statement.setString(9, value.icon());
                        statement.setString(10, value.displayName());
                        statement.setString(11, value.description());
                        statement.setLong(12, value.createdAt());
                        statement.executeUpdate();
                    }
                })
                .thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(WarpKey key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM island_warps WHERE island_id = ? AND name = ?")) {
                        statement.setObject(1, key.islandId());
                        statement.setString(2, key.name());
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected CompletableFuture<List<IslandWarp>> loadByIndex(UUID islandId) {
        @Language("SQL") String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ? ORDER BY created_at";
        return this.plugin.database()
                .supply(connection -> {
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
    protected UUID indexKeyOf(IslandWarp value) {
        return value.islandId();
    }

    @Override
    protected void publishUpdate(WarpKey key, IslandWarp value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.name()));
    }

    @Override
    protected void publishInvalidation(WarpKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.name()));
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

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
                resultSet.getString("icon"),
                resultSet.getString("display_name"),
                resultSet.getString("description"),
                resultSet.getLong("created_at")
        );
    }
}
