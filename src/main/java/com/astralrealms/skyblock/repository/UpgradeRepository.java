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
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.model.island.UpgradeKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island upgrade levels, keyed by the composite {@link UpgradeKey} (island + upgrade name). */
public class UpgradeRepository extends IndexedSyncedRepository<UpgradeKey, IslandUpgrade, UUID> {

    private static final String COLUMNS = "island_id, upgrade, level";

    public UpgradeRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.UPGRADE_CACHE_KEY, ASConstants.UPGRADE_UPDATE_CHANNEL, IslandUpgrade.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new UpgradeKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new UpgradeKey(delete.islandId(), delete.key()));
        });
    }

    public CompletableFuture<List<IslandUpgrade>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandUpgrade> setLevel(UUID islandId, String upgrade, int level) {
        return save(new IslandUpgrade(islandId, upgrade, level));
    }

    public CompletableFuture<IslandUpgrade> remove(UUID islandId, String upgrade) {
        return delete(new UpgradeKey(islandId, upgrade));
    }

    @Override
    protected UUID indexKeyOf(IslandUpgrade value) {
        return value.islandId();
    }

    @Override
    protected UpgradeKey keyFromValue(IslandUpgrade value) {
        return new UpgradeKey(value.islandId(), value.upgrade());
    }

    @Override
    protected String cacheKey(UpgradeKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.upgrade();
    }

    @Override
    protected CompletableFuture<IslandUpgrade> loadById(UpgradeKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_upgrades WHERE island_id = ? AND upgrade = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.upgrade());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandUpgrade>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_upgrades WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandUpgrade> upgrades = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        upgrades.add(map(resultSet));
                }
            }
            return upgrades;
        });
    }

    @Override
    protected CompletableFuture<IslandUpgrade> saveToDatabase(IslandUpgrade value) {
        String query = "INSERT INTO island_upgrades (island_id, upgrade, level) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE level = VALUES(level)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.upgrade());
                statement.setInt(3, value.level());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(UpgradeKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM island_upgrades WHERE island_id = ? AND upgrade = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.upgrade());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(UpgradeKey key, IslandUpgrade value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.upgrade()));
    }

    @Override
    protected void publishInvalidation(UpgradeKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.upgrade()));
    }

    private IslandUpgrade map(ResultSet resultSet) throws SQLException {
        return new IslandUpgrade(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("upgrade"),
                resultSet.getInt("level")
        );
    }
}
