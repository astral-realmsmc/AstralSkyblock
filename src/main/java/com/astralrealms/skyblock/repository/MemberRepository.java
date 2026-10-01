package com.astralrealms.skyblock.repository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.service.IslandFullException;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Island membership, keyed by the composite {@link MemberKey} (island + player) that mirrors the
 * {@code island_members} primary key. {@link IslandMember} carries no {@code @Id}, so rows are
 * mapped by hand rather than through the framework {@code RowMapper}.
 *
 * <p>Note: {@code uq_member_player} guarantees a player belongs to at most one island, so
 * {@link #findByPlayer(UUID)} resolves a single membership.
 */
public class MemberRepository extends IndexedSyncedRepository<MemberKey, IslandMember, UUID> {

    private static final String COLUMNS = "island_id, player_uuid, is_owner, role_id, joined_at";
    private final Map<UUID, UUID> playerIslandsMap = new ConcurrentHashMap<>();

    public MemberRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.MEMBER_CACHE_KEY,
                ASConstants.MEMBER_UPDATE_CHANNEL,
                IslandMember.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            // Packets can arrive while the plugin is still enabling. Nothing is cached yet then, so
            // there is nothing to refresh; dropping them is safe.
            if (this.plugin.islands() == null)
                return;
            if (packet instanceof MemberObjectUpdatePacket updatePacket) {
                // Keep the member entry coherent for direct lookups, then rebuild the island's
                // relationship snapshot (which also re-primes the slice when the island is cached here).
                cache.synchronous().refresh(new MemberKey(updatePacket.islandId(), updatePacket.playerUuid()));
                this.plugin.islands().refreshRelationships(updatePacket.islandId());
            } else if (packet instanceof MemberObjectDeletePacket deletePacket) {
                // Evict the removed member from L1 (a re-prime would not drop a deleted row), then
                // rebuild the island's relationship snapshot.
                invalidateLocally(new MemberKey(deletePacket.islandId(), deletePacket.playerUuid()));
                this.plugin.islands().refreshRelationships(deletePacket.islandId());
            }
        });
    }

    @Unmodifiable
    public Collection<UUID> findIslandMembers(UUID islandId) {
        return keysIn(islandId).stream().map(MemberKey::playerUuid).toList();
    }

    @Unmodifiable
    public Optional<UUID> findPlayerIsland(UUID playerUuid) {
        return Optional.ofNullable(this.playerIslandsMap.get(playerUuid));
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * Every member of an island. Primes the per-member cache and indexes.
     */
    public CompletableFuture<List<IslandMember>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /**
     * The single membership of a player, or {@code null} if they belong to no island.
     */
    public CompletableFuture<IslandMember> findByPlayer(UUID playerUuid) {
        String query = "SELECT " + COLUMNS + " FROM island_members WHERE player_uuid = ?";
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, playerUuid);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? map(resultSet) : null;
                        }
                    }
                })
                .thenApply(member -> {
                    if (member != null)
                        cacheLocally(member);
                    return member;
                });
    }

    /**
     * Adds a member with a role, as long as the island holds fewer than {@code limit} members.
     * The island row is locked while counting, so two joins racing on two servers cannot both
     * take the last slot. Fails with {@link IslandFullException} when the island is full, and on
     * {@code uq_member_player} if the player already belongs to an island.
     */
    public CompletableFuture<IslandMember> add(UUID islandId, UUID playerUuid, long roleId, int limit) {
        return this.plugin.database()
                .transactionSupply(connection -> {
                    lockIsland(connection, islandId);
                    if (countRows(connection, "SELECT COUNT(*) FROM island_members WHERE island_id = ?", islandId) >= limit)
                        throw new IslandFullException(islandId, limit, true);

                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO island_members (island_id, player_uuid, is_owner, role_id) VALUES (?, ?, FALSE, ?)")) {
                        statement.setObject(1, islandId);
                        statement.setObject(2, playerUuid);
                        statement.setLong(3, roleId);
                        statement.executeUpdate();
                    }
                    return null;
                })
                .thenCompose(ignored -> reload(islandId, playerUuid));
    }

    /** Takes a row lock on the island, serialising every capped write to it until commit. */
    static void lockIsland(Connection connection, UUID islandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM islands WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, islandId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next())
                    throw new SQLException("Island " + islandId + " no longer exists");
            }
        }
    }

    static long countRows(Connection connection, String query, UUID islandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setObject(1, islandId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    /**
     * Removes a member (leave / kick). The {@code is_owner} guard prevents removing the owner.
     */
    public CompletableFuture<Void> remove(UUID islandId, UUID playerUuid) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_members WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        statement.setObject(1, islandId);
                        statement.setObject(2, playerUuid);
                        return statement.executeUpdate();
                    }
                })
                .thenCompose(removed -> {
                    invalidateGlobally(new MemberKey(islandId, playerUuid));
                    return this.plugin.islands().refreshRelationships(islandId)
                            .thenRun(() -> requireRow(removed, islandId, playerUuid));
                });
    }

    /**
     * Sets a member's role (promote / demote). The owner is excluded by the guard.
     */
    public CompletableFuture<IslandMember> setRole(UUID islandId, UUID playerUuid, long roleId) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE island_members SET role_id = ? WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        statement.setLong(1, roleId);
                        statement.setObject(2, islandId);
                        statement.setObject(3, playerUuid);
                        return statement.executeUpdate();
                    }
                })
                .thenCompose(updated -> reload(islandId, playerUuid)
                        .thenApply(member -> {
                            requireRow(updated, islandId, playerUuid);
                            return member;
                        }));
    }

    /**
     * A write that matched no row: the member left or was removed (often on another server) after
     * the caller looked them up. The snapshot has been refreshed either way; the caller must not
     * report a kick or a promotion that never happened.
     */
    public static final class MemberNotFoundException extends IllegalStateException {
        MemberNotFoundException(UUID islandId, UUID playerUuid) {
            super(playerUuid + " is no longer a member of island " + islandId);
        }
    }

    /** Whether {@code throwable} (or its cause) is a {@link MemberNotFoundException}. */
    public static boolean isMemberGone(Throwable throwable) {
        return throwable instanceof MemberNotFoundException
               || (throwable != null && throwable.getCause() instanceof MemberNotFoundException);
    }

    private static void requireRow(int rows, UUID islandId, UUID playerUuid) {
        if (rows == 0)
            throw new MemberNotFoundException(islandId, playerUuid);
    }

    /**
     * Transfers ownership (transactional). The old owner is demoted into {@code exOwnerRoleId}
     * first (freeing {@code owner_guard} and satisfying the owner&lt;=&gt;no-role CHECK in the same
     * statement), then the new owner — who must already be a member — is promoted. Statement order
     * is mandatory: {@code uq_single_owner} rejects two owners mid-flight.
     */
    public CompletableFuture<Boolean> transferOwnership(UUID islandId, UUID oldOwner, long exOwnerRoleId, UUID newOwner) {
        return this.plugin.database()
                .transaction(connection -> {
                    try (PreparedStatement demote = connection.prepareStatement("UPDATE island_members SET is_owner = FALSE, role_id = ? WHERE island_id = ? AND player_uuid = ? AND is_owner = TRUE")) {
                        demote.setLong(1, exOwnerRoleId);
                        demote.setObject(2, islandId);
                        demote.setObject(3, oldOwner);
                        if (demote.executeUpdate() != 1)
                            throw new SQLException("Island " + islandId + ": " + oldOwner + " is no longer the owner");
                    }
                    // The new owner may have left or been kicked on another server since the caller
                    // checked; committing the demote alone would leave the island without an owner.
                    try (PreparedStatement promote = connection.prepareStatement("UPDATE island_members SET is_owner = TRUE, role_id = NULL WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        promote.setObject(1, islandId);
                        promote.setObject(2, newOwner);
                        if (promote.executeUpdate() != 1)
                            throw new SQLException("Island " + islandId + ": " + newOwner + " is no longer a member");
                    }
                })
                .thenCompose(success -> {
                    invalidateGlobally(new MemberKey(islandId, oldOwner));
                    invalidateGlobally(new MemberKey(islandId, newOwner));
                    return this.plugin.islands().refreshRelationships(islandId)
                            .thenApply(ignored -> success);
                });
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // members are local-cache + database only
    }

    @Override
    protected MemberKey keyFromValue(IslandMember value) {
        return new MemberKey(value.islandId(), value.playerUuid());
    }

    @Override
    protected String cacheKey(MemberKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.playerUuid();
    }

    @Override
    protected CompletableFuture<IslandMember> loadById(MemberKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_members WHERE island_id = ? AND player_uuid = ?";
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
    protected CompletableFuture<IslandMember> saveToDatabase(IslandMember value) {
        String query = "INSERT INTO island_members (island_id, player_uuid, is_owner, role_id) VALUES (?, ?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE is_owner = VALUES(is_owner), role_id = VALUES(role_id)";
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, value.islandId());
                        statement.setObject(2, value.playerUuid());
                        statement.setBoolean(3, value.isOwner());
                        if (value.roleId() == null)
                            statement.setNull(4, Types.BIGINT);
                        else
                            statement.setLong(4, value.roleId());
                        statement.executeUpdate();
                    }
                })
                .thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(MemberKey key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_members WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        statement.setObject(1, key.islandId());
                        statement.setObject(2, key.playerUuid());
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected void publishUpdate(MemberKey key, IslandMember value) {
        this.plugin.messaging().send(exchangeChannel, new MemberObjectUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(MemberKey key) {
        this.plugin.messaging().send(exchangeChannel, new MemberObjectDeletePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected UUID indexKeyOf(IslandMember value) {
        return value.islandId();
    }

    @Override
    protected CompletableFuture<List<IslandMember>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_members WHERE island_id = ?";
        return this.plugin.database()
                .supply(connection -> {
                    List<IslandMember> members = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next())
                                members.add(map(resultSet));
                        }
                    }
                    return members;
                });
    }

    @Override
    protected void index(IslandMember value) {
        super.index(value);
        this.playerIslandsMap.put(value.playerUuid(), value.islandId());
    }

    @Override
    protected void deindex(MemberKey key, IslandMember value) {
        super.deindex(key, value);
        this.playerIslandsMap.remove(key.playerUuid(), key.islandId());
    }

    @Override
    protected void onPrimed(UUID islandId, List<MemberKey> removed, List<IslandMember> values) {
        // A member who left or was kicked on another server must stop resolving to this island.
        removed.forEach(key -> this.playerIslandsMap.remove(key.playerUuid(), key.islandId()));
        values.forEach(member -> this.playerIslandsMap.put(member.playerUuid(), member.islandId()));
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private CompletableFuture<IslandMember> reload(UUID islandId, UUID playerUuid) {
        MemberKey key = new MemberKey(islandId, playerUuid);
        invalidateGlobally(key);
        return findById(key)
                .thenCompose(member -> this.plugin.islands()
                        .refreshRelationships(islandId)
                        .thenApply(ignored -> member));
    }

    private IslandMember map(ResultSet resultSet) throws SQLException {
        UUID islandId = resultSet.getObject("island_id", UUID.class);
        UUID playerUuid = resultSet.getObject("player_uuid", UUID.class);
        boolean isOwner = resultSet.getBoolean("is_owner");
        long roleId = resultSet.getLong("role_id");
        Long role = resultSet.wasNull() ? null : roleId;
        Timestamp joinedAt = resultSet.getTimestamp("joined_at");
        return new IslandMember(islandId, playerUuid, isOwner, role, joinedAt == null ? 0L : joinedAt.getTime());
    }
}
