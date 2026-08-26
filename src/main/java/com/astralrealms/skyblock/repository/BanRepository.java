package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.intellij.lang.annotations.Language;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Repository for island bans, keyed by the composite {@link IslandPlayerKey} (island + player) that
 * mirrors the {@code island_bans} primary key.
 *
 * <p>A secondary map ({@code playerBannedIslandsMap}) makes {@link #isBanned(UUID, UUID)} an O(1)
 * in-memory check, which matters because it is consulted on every teleport into an island world.
 * The map is only accurate for islands whose slice has been primed — the island cascade primes it
 * for every cached island, and {@link #findByIsland(UUID)} primes it on demand.
 *
 * <p>Cross-server coherency flows through {@link IslandPlayerKeyUpdatePacket}/
 * {@link IslandPlayerKeyDeletePacket} on the ban channel; there is no shared L2 cache, so an update
 * refreshes the entry from the database.
 */
public class BanRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandBan, UUID> {

    // playerUuid -> set of islandIds the player is currently banned from
    private final Map<UUID, Set<UUID>> playerBannedIslandsMap = new ConcurrentHashMap<>();

    public BanRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.BAN_CACHE_KEY, ASConstants.BAN_UPDATE_CHANNEL, IslandBan.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            // Packets can arrive before IslandService exists (this repository is built first).
            if (this.plugin.islands() == null)
                return;

            if (packet instanceof IslandPlayerKeyUpdatePacket update) {
                // The entry may be brand new here (ban issued on another server); refresh both
                // reloads a cached ban and loads a missing one.
                cache.synchronous().refresh(new IslandPlayerKey(update.islandId(), update.playerUuid()))
                        .thenAccept(ignored -> this.plugin.islands().refreshBans(update.islandId()));
            } else if (packet instanceof IslandPlayerKeyDeletePacket delete) {
                invalidateLocally(new IslandPlayerKey(delete.islandId(), delete.playerUuid()));
                this.plugin.islands().refreshBans(delete.islandId());
            }
        });
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * Every ban of an island. Primes the per-entry cache and the island's index slice, which makes
     * {@link #isBanned(UUID, UUID)} accurate for it.
     */
    public CompletableFuture<List<IslandBan>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /**
     * O(1) ban check backed by the in-memory secondary map. The map is populated whenever an entry
     * is indexed (via {@link #prime}, {@link #cacheLocally}, or the async loader) and pruned when
     * entries are deindexed.
     */
    public boolean isBanned(UUID islandId, UUID playerUuid) {
        return playerBannedIslandsMap
                .getOrDefault(playerUuid, Collections.emptySet())
                .contains(islandId);
    }

    /** Reads a single ban through the cache, falling back to the database on a miss. */
    public CompletableFuture<Optional<IslandBan>> findBan(UUID islandId, UUID playerUuid) {
        return findById(new IslandPlayerKey(islandId, playerUuid)).thenApply(Optional::ofNullable);
    }

    /** Persists a ban and writes it through the local cache, publishing it to other servers. */
    public CompletableFuture<IslandBan> ban(IslandBan ban) {
        return save(ban);
    }

    /** Deletes a ban, evicting it everywhere. */
    public CompletableFuture<Void> unban(UUID islandId, UUID playerUuid) {
        return delete(new IslandPlayerKey(islandId, playerUuid)).thenAccept(ignored -> {
        });
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // bans are local-cache + database only; cross-server sync via packets
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
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, banned_by, reason, created_at
                FROM island_bans WHERE island_id = ? AND player_uuid = ?
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
    protected CompletableFuture<IslandBan> saveToDatabase(IslandBan value) {
        @Language("SQL") String query = """
                INSERT INTO island_bans (island_id, player_uuid, banned_by, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE banned_by = VALUES(banned_by), reason = VALUES(reason)
                """;
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, value.islandId());
                        statement.setObject(2, value.playerUuid());
                        statement.setObject(3, value.bannedBy());
                        statement.setString(4, value.reason());
                        statement.setLong(5, value.createdAt());
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
                            "DELETE FROM island_bans WHERE island_id = ? AND player_uuid = ?")) {
                        statement.setObject(1, key.islandId());
                        statement.setObject(2, key.playerUuid());
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected CompletableFuture<List<IslandBan>> loadByIndex(UUID islandId) {
        @Language("SQL") String query = """
                SELECT island_id, player_uuid, banned_by, reason, created_at
                FROM island_bans WHERE island_id = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
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
    protected UUID indexKeyOf(IslandBan value) {
        return value.islandId();
    }

    @Override
    protected void index(IslandBan value) {
        super.index(value);
        playerBannedIslandsMap
                .computeIfAbsent(value.playerUuid(), ignored -> ConcurrentHashMap.newKeySet())
                .add(value.islandId());
    }

    @Override
    protected void deindex(IslandPlayerKey key, IslandBan value) {
        super.deindex(key, value);
        Set<UUID> islands = playerBannedIslandsMap.get(key.playerUuid());
        if (islands == null)
            return;
        islands.remove(key.islandId());
        if (islands.isEmpty())
            playerBannedIslandsMap.remove(key.playerUuid());
    }

    /**
     * A prime replaces the island's whole slice, so entries that disappeared from the database must
     * also leave the secondary map — otherwise a ban lifted on another server would keep blocking
     * the player here until the next restart.
     */
    @Override
    protected void onPrimed(UUID islandId, List<IslandBan> values) {
        Set<UUID> stillBanned = values.stream().map(IslandBan::playerUuid).collect(java.util.stream.Collectors.toSet());
        playerBannedIslandsMap.forEach((playerUuid, islands) -> {
            if (!stillBanned.contains(playerUuid))
                islands.remove(islandId);
        });
        playerBannedIslandsMap.values().removeIf(Set::isEmpty);
        values.forEach(this::index);
    }

    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandBan value) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyDeletePacket(key.islandId(), key.playerUuid()));
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private IslandBan map(ResultSet resultSet) throws SQLException {
        String bannedBy = resultSet.getString("banned_by");
        return new IslandBan(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                bannedBy != null ? UUID.fromString(bannedBy) : null,
                resultSet.getString("reason"),
                resultSet.getLong("created_at")
        );
    }
}
