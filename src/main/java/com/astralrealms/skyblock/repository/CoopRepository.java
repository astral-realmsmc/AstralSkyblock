package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Repository for island co-op membership, keyed by the composite {@link IslandPlayerKey}
 * (island + player) that mirrors the {@code island_coops} primary key.
 *
 * <p>A secondary map ({@code playerCoopIslandsMap}) allows O(1) {@link #isCoop(UUID, UUID)} checks
 * without hitting the database or even the primary index. A player may be coop on multiple islands.
 *
 * <p>Cache coherency across servers is delegated to the service layer via
 * {@code CoopAddPacket}/{@code CoopRemovePacket}; this repository therefore has no-op
 * {@link #publishUpdate} and {@link #publishInvalidation} implementations.
 */
public class CoopRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandCoop, UUID> {

    // playerUuid -> set of islandIds where the player is currently a coop member
    private final Map<UUID, Set<UUID>> playerCoopIslandsMap = new ConcurrentHashMap<>();

    public CoopRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.COOP_CACHE_KEY, ASConstants.COOP_UPDATE_CHANNEL, IslandCoop.class);
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * All coop members of an island. Primes the per-entry cache and the primary index slice for
     * {@code islandId} before returning the snapshot.
     */
    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return prime(islandId).thenApply(ignored ->
                keysIn(islandId).stream()
                        .map(key -> findCachedById(key).orElse(null))
                        .filter(Objects::nonNull)
                        .toList()
        );
    }

    /**
     * O(1) membership check backed by the in-memory secondary map. The map is populated whenever an
     * entry is indexed (via {@link #prime}, {@link #cacheLocally}, or the async cache loader) and
     * pruned when entries are deindexed.
     */
    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return playerCoopIslandsMap
                .getOrDefault(playerUuid, Collections.emptySet())
                .contains(islandId);
    }

    /**
     * Persists a new coop entry and caches it locally. Use this rather than the raw
     * {@link #saveToDatabase} so the local index is kept in sync.
     */
    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return saveToDatabase(coop).thenApply(saved -> {
            cacheLocally(saved);
            return saved;
        });
    }

    /**
     * Adds a coop entry to the local L1 cache and secondary index without persisting it to the
     * database. Used by {@link com.astralrealms.skyblock.service.CoopService} when handling a
     * {@code CoopAddPacket} from another server so that {@link #isCoop(UUID, UUID)} stays accurate.
     */
    public void addLocally(IslandCoop value) {
        cacheLocally(value);
    }

    /**
     * Removes a coop entry from the database and evicts it from the local cache. The service layer
     * is responsible for broadcasting the removal to other servers via CoopRemovePacket.
     */
    public CompletableFuture<Void> remove(UUID islandId, UUID playerUuid) {
        IslandPlayerKey key = new IslandPlayerKey(islandId, playerUuid);
        return deleteFromDatabase(key).thenAccept(ignored -> invalidateLocally(key));
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // coops are local-cache + database only; cross-server sync via packets
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
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, added_by, created_at
                FROM island_coops WHERE island_id = ? AND player_uuid = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
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
    protected CompletableFuture<IslandCoop> saveToDatabase(IslandCoop value) {
        @Language("SQL") String query = """
                INSERT INTO island_coops (island_id, player_uuid, added_by, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE added_by = VALUES(added_by)
                """;
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, value.islandId());
                        statement.setObject(2, value.playerUuid());
                        statement.setObject(3, value.addedBy());
                        statement.setLong(4, value.createdAt());
                        statement.executeUpdate();
                    }
                })
                .thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM island_coops WHERE island_id = ? AND player_uuid = ?")) {
                        statement.setObject(1, key.islandId());
                        statement.setObject(2, key.playerUuid());
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected CompletableFuture<List<IslandCoop>> loadByIndex(UUID islandId) {
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, added_by, created_at
                FROM island_coops WHERE island_id = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
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
    protected UUID indexKeyOf(IslandCoop value) {
        return value.islandId();
    }

    @Override
    protected void index(IslandCoop value) {
        super.index(value);
        playerCoopIslandsMap
                .computeIfAbsent(value.playerUuid(), k -> ConcurrentHashMap.newKeySet())
                .add(value.islandId());
    }

    @Override
    protected void deindex(IslandPlayerKey key, IslandCoop value) {
        super.deindex(key, value);
        Set<UUID> ids = playerCoopIslandsMap.get(key.playerUuid());
        if (ids == null) return;
        ids.remove(key.islandId());
        if (ids.isEmpty()) playerCoopIslandsMap.remove(key.playerUuid());
    }

    // Cache coherency is handled via CoopAddPacket/CoopRemovePacket at the service layer.
    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandCoop value) {}

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {}

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private IslandCoop map(ResultSet resultSet) throws java.sql.SQLException {
        String addedBy = resultSet.getString("added_by");
        return new IslandCoop(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                addedBy != null ? UUID.fromString(addedBy) : null,
                resultSet.getLong("created_at")
        );
    }
}
