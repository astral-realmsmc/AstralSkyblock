package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island bans, keyed by the composite {@link IslandPlayerKey} (island + player). */
public class BanRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandBan, UUID> {

    private static final String COLUMNS = "island_id, player_uuid, banned_by, reason, created_at";

    public BanRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.BAN_CACHE_KEY, ASConstants.BAN_UPDATE_CHANNEL, IslandBan.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandPlayerKeyUpdatePacket update)
                cache.synchronous().refresh(new IslandPlayerKey(update.islandId(), update.playerUuid()));
            else if (packet instanceof IslandPlayerKeyDeletePacket delete)
                invalidateLocally(new IslandPlayerKey(delete.islandId(), delete.playerUuid()));
        });
    }

    public CompletableFuture<List<IslandBan>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandBan> ban(IslandBan ban) {
        return save(ban);
    }

    public CompletableFuture<IslandBan> unban(UUID islandId, UUID playerUuid) {
        return delete(new IslandPlayerKey(islandId, playerUuid));
    }

    @Override
    protected UUID indexKeyOf(IslandBan value) {
        return value.islandId();
    }

    @Override
    protected IslandPlayerKey keyFromValue(IslandBan value) {
        return new IslandPlayerKey(value.islandId(), value.playerUuid());
    }

    @Override
    protected String cacheKey(IslandPlayerKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.playerUuid();
    }

    @Override
    protected CompletableFuture<IslandBan> loadById(IslandPlayerKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_bans WHERE island_id = ? AND player_uuid = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setObject(2, key.playerUuid());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandBan>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_bans WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandBan> bans = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        bans.add(map(resultSet));
                }
            }
            return bans;
        });
    }

    @Override
    protected CompletableFuture<IslandBan> saveToDatabase(IslandBan value) {
        String query = "INSERT INTO island_bans (island_id, player_uuid, banned_by, reason) VALUES (?, ?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE banned_by = VALUES(banned_by), reason = VALUES(reason)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setObject(2, value.playerUuid());
                statement.setObject(3, value.bannedBy());
                statement.setString(4, value.reason());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM island_bans WHERE island_id = ? AND player_uuid = ?")) {
                statement.setObject(1, key.islandId());
                statement.setObject(2, key.playerUuid());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandBan value) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyDeletePacket(key.islandId(), key.playerUuid()));
    }

    private IslandBan map(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new IslandBan(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                resultSet.getObject("banned_by", UUID.class),
                resultSet.getString("reason"),
                createdAt == null ? 0L : createdAt.getTime()
        );
    }
}
