package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.model.island.UpgradeKey;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Repository for island upgrade levels, keyed by the composite {@link UpgradeKey}
 * (island + upgrade key) that mirrors the {@code island_upgrades} primary key.
 *
 * <p>Rows are override-only: an absent row means level 0 (the configured default), so a default
 * island carries no rows at all. Cross-server coherency flows through
 * {@link IslandStringKeyUpdatePacket}/{@link IslandStringKeyDeletePacket} on the upgrade channel;
 * there is no shared L2 cache, so an update refreshes the entry from the database.
 */
public class UpgradeRepository extends IndexedSyncedRepository<UpgradeKey, IslandUpgrade, UUID> {

    public UpgradeRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.UPGRADE_CACHE_KEY,
                ASConstants.UPGRADE_UPDATE_CHANNEL,
                IslandUpgrade.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket updatePacket) {
                // The entry may be brand new here (first purchase made on another server); refresh
                // both reloads a cached level and loads a missing one.
                cache.synchronous().refresh(new UpgradeKey(updatePacket.islandId(), updatePacket.key()))
                        .thenAccept(ignored -> this.plugin.islands().refreshUpgrades(updatePacket.islandId()));
            } else if (packet instanceof IslandStringKeyDeletePacket deletePacket) {
                invalidateLocally(new UpgradeKey(deletePacket.islandId(), deletePacket.key()));
                this.plugin.islands().refreshUpgrades(deletePacket.islandId());
            }
        });
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * All stored upgrade levels of an island. Primes the per-entry cache and the island's index
     * slice, which makes {@link #level(UUID, String)} accurate for that island.
     */
    public CompletableFuture<List<IslandUpgrade>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /**
     * O(1) cached lookup of an island's level for {@code upgrade}. Returns 0 when no row exists
     * (the override-only default) — or when the island's slice has not been primed yet; the island
     * cascade primes it for every cached island, so this is accurate for them.
     */
    public int level(UUID islandId, String upgrade) {
        return findCachedById(new UpgradeKey(islandId, upgrade))
                .map(IslandUpgrade::level)
                .orElse(0);
    }

    /**
     * Reads an island's level for {@code upgrade} through the cache, falling back to the database
     * on a miss; an absent row resolves to 0.
     */
    public CompletableFuture<Integer> findLevel(UUID islandId, String upgrade) {
        return findById(new UpgradeKey(islandId, upgrade))
                .thenApply(value -> value == null ? 0 : value.level());
    }

    /**
     * Upserts an island's level for {@code upgrade}, writes it through the local cache, and
     * publishes the update so other servers refresh their copy.
     */
    public CompletableFuture<IslandUpgrade> setLevel(UUID islandId, String upgrade, int level) {
        return save(new IslandUpgrade(islandId, upgrade, level));
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // upgrades are local-cache + database only; cross-server sync via packets
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
        @Language("SQL") String query = """
                SELECT island_id, upgrade, level
                FROM island_upgrades WHERE island_id = ? AND upgrade = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
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
    protected CompletableFuture<IslandUpgrade> saveToDatabase(IslandUpgrade value) {
        @Language("SQL") String query = """
                INSERT INTO island_upgrades (island_id, upgrade, level)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE level = VALUES(level)
                """;
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, value.islandId());
                        statement.setString(2, value.upgrade());
                        statement.setInt(3, value.level());
                        statement.executeUpdate();
                    }
                })
                .thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(UpgradeKey key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM island_upgrades WHERE island_id = ? AND upgrade = ?")) {
                        statement.setObject(1, key.islandId());
                        statement.setString(2, key.upgrade());
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected CompletableFuture<List<IslandUpgrade>> loadByIndex(UUID islandId) {
        @Language("SQL") String query = """
                SELECT island_id, upgrade, level
                FROM island_upgrades WHERE island_id = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
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
    protected UUID indexKeyOf(IslandUpgrade value) {
        return value.islandId();
    }

    @Override
    protected void publishUpdate(UpgradeKey key, IslandUpgrade value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.upgrade()));
    }

    @Override
    protected void publishInvalidation(UpgradeKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.upgrade()));
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private IslandUpgrade map(ResultSet resultSet) throws SQLException {
        return new IslandUpgrade(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("upgrade"),
                resultSet.getInt("level")
        );
    }
}
