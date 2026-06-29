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
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island coop grants, keyed by the composite {@link IslandPlayerKey} (island + player). */
public class CoopRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandCoop, UUID> {

    private static final String COLUMNS = "island_id, player_uuid, added_by, created_at";

    public CoopRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.COOP_CACHE_KEY, ASConstants.COOP_UPDATE_CHANNEL, IslandCoop.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandPlayerKeyUpdatePacket update)
                cache.synchronous().refresh(new IslandPlayerKey(update.islandId(), update.playerUuid()));
            else if (packet instanceof IslandPlayerKeyDeletePacket delete)
                invalidateLocally(new IslandPlayerKey(delete.islandId(), delete.playerUuid()));
        });
    }

    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return save(coop);
    }

    public CompletableFuture<IslandCoop> remove(UUID islandId, UUID playerUuid) {
        return delete(new IslandPlayerKey(islandId, playerUuid));
    }

    @Override
    protected UUID indexKeyOf(IslandCoop value) {
        return value.islandId();
    }

    @Override
    protected IslandPlayerKey keyFromValue(IslandCoop value) {
        return new IslandPlayerKey(value.islandId(), value.playerUuid());
    }

    @Override
    protected String cacheKey(IslandPlayerKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.playerUuid();
    }

    @Override
    protected CompletableFuture<IslandCoop> loadById(IslandPlayerKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_coops WHERE island_id = ? AND player_uuid = ?";
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
    protected CompletableFuture<List<IslandCoop>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_coops WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandCoop> coops = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        coops.add(map(resultSet));
                }
            }
            return coops;
        });
    }

    @Override
    protected CompletableFuture<IslandCoop> saveToDatabase(IslandCoop value) {
        String query = "INSERT INTO island_coops (island_id, player_uuid, added_by) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE added_by = VALUES(added_by)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setObject(2, value.playerUuid());
                statement.setObject(3, value.addedBy());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM island_coops WHERE island_id = ? AND player_uuid = ?")) {
                statement.setObject(1, key.islandId());
                statement.setObject(2, key.playerUuid());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandCoop value) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyDeletePacket(key.islandId(), key.playerUuid()));
    }

    private IslandCoop map(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new IslandCoop(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                resultSet.getObject("added_by", UUID.class),
                createdAt == null ? 0L : createdAt.getTime()
        );
    }
}
