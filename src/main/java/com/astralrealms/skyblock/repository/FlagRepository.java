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
import com.astralrealms.skyblock.model.island.FlagKey;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island protection flags, keyed by the composite {@link FlagKey} (island + flag name). */
public class FlagRepository extends IndexedSyncedRepository<FlagKey, IslandFlag, UUID> {

    private static final String COLUMNS = "island_id, flag, allowed";

    public FlagRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.FLAG_CACHE_KEY, ASConstants.FLAG_UPDATE_CHANNEL, IslandFlag.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new FlagKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new FlagKey(delete.islandId(), delete.key()));
        });
    }

    public CompletableFuture<List<IslandFlag>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandFlag> set(UUID islandId, String flag, boolean allowed) {
        return save(new IslandFlag(islandId, flag, allowed));
    }

    public CompletableFuture<IslandFlag> remove(UUID islandId, String flag) {
        return delete(new FlagKey(islandId, flag));
    }

    @Override
    protected UUID indexKeyOf(IslandFlag value) {
        return value.islandId();
    }

    @Override
    protected FlagKey keyFromValue(IslandFlag value) {
        return new FlagKey(value.islandId(), value.flag());
    }

    @Override
    protected String cacheKey(FlagKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.flag();
    }

    @Override
    protected CompletableFuture<IslandFlag> loadById(FlagKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_flags WHERE island_id = ? AND flag = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.flag());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandFlag>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_flags WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandFlag> flags = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        flags.add(map(resultSet));
                }
            }
            return flags;
        });
    }

    @Override
    protected CompletableFuture<IslandFlag> saveToDatabase(IslandFlag value) {
        String query = "INSERT INTO island_flags (island_id, flag, allowed) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE allowed = VALUES(allowed)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.flag());
                statement.setBoolean(3, value.allowed());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(FlagKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM island_flags WHERE island_id = ? AND flag = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.flag());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(FlagKey key, IslandFlag value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.flag()));
    }

    @Override
    protected void publishInvalidation(FlagKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.flag()));
    }

    private IslandFlag map(ResultSet resultSet) throws SQLException {
        return new IslandFlag(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("flag"),
                resultSet.getBoolean("allowed")
        );
    }
}
