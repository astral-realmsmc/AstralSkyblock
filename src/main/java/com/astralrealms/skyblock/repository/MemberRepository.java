package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.MemberObjectUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Island membership, keyed by the composite {@link MemberKey} (island + player) that mirrors the
 * {@code island_members} primary key. {@link IslandMember} carries no {@code @Id}, so rows are
 * mapped by hand rather than through the framework {@code RowMapper}.
 *
 * <p>Note: {@code uq_member_player} guarantees a player belongs to at most one island, so
 * {@link #findByPlayer(UUID)} resolves a single membership.
 */
public class MemberRepository extends SyncedRepository<MemberKey, IslandMember> {

    private static final String COLUMNS = "island_id, player_uuid, is_owner, role_id, joined_at";

    public MemberRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.MEMBER_CACHE_KEY,
                ASConstants.MEMBER_UPDATE_CHANNEL,
                cacheLoader -> Caffeine.newBuilder()
                        .maximumSize(250_000)
                        .buildAsync(cacheLoader),
                IslandMember.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof MemberObjectUpdatePacket updatePacket)
                cache.synchronous().refresh(new MemberKey(updatePacket.islandId(), updatePacket.playerUuid()));
            else if (packet instanceof MemberObjectDeletePacket deletePacket)
                invalidateLocally(new MemberKey(deletePacket.islandId(), deletePacket.playerUuid()));
        });
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /** Every member of an island. Primes the per-member cache. */
    public CompletableFuture<List<IslandMember>> findByIsland(UUID islandId) {
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
                })
                .thenApply(members -> {
                    members.forEach(member -> cache.synchronous().put(keyFromValue(member), member));
                    return members;
                });
    }

    /** The single membership of a player, or {@code null} if they belong to no island. */
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
                        cache.synchronous().put(keyFromValue(member), member);
                    return member;
                });
    }

    /** The owner of an island (via the {@code owner_guard} unique virtual column), or {@code null}. */
    public CompletableFuture<UUID> findOwner(UUID islandId) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM island_members WHERE owner_guard = ?")) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getObject("player_uuid", UUID.class) : null;
                        }
                    }
                });
    }

    /** Adds a member with a role. Fails on {@code uq_member_player} if they already belong to an island. */
    public CompletableFuture<IslandMember> add(UUID islandId, UUID playerUuid, long roleId) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO island_members (island_id, player_uuid, is_owner, role_id) VALUES (?, ?, FALSE, ?)")) {
                        statement.setObject(1, islandId);
                        statement.setObject(2, playerUuid);
                        statement.setLong(3, roleId);
                        statement.executeUpdate();
                    }
                })
                .thenCompose(ignored -> reload(islandId, playerUuid));
    }

    /** Adds the structural owner (no role). Used during island creation. */
    public CompletableFuture<IslandMember> addOwner(UUID islandId, UUID ownerUuid) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO island_members (island_id, player_uuid, is_owner, role_id) VALUES (?, ?, TRUE, NULL)")) {
                        statement.setObject(1, islandId);
                        statement.setObject(2, ownerUuid);
                        statement.executeUpdate();
                    }
                })
                .thenCompose(ignored -> reload(islandId, ownerUuid));
    }

    /** Removes a member (leave / kick). The {@code is_owner} guard prevents removing the owner. */
    public CompletableFuture<Void> remove(UUID islandId, UUID playerUuid) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_members WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        statement.setObject(1, islandId);
                        statement.setObject(2, playerUuid);
                        statement.executeUpdate();
                    }
                })
                .thenRun(() -> invalidateGlobally(new MemberKey(islandId, playerUuid)));
    }

    /** Sets a member's role (promote / demote). The owner is excluded by the guard. */
    public CompletableFuture<IslandMember> setRole(UUID islandId, UUID playerUuid, long roleId) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE island_members SET role_id = ? WHERE island_id = ? AND player_uuid = ? AND is_owner = FALSE")) {
                        statement.setLong(1, roleId);
                        statement.setObject(2, islandId);
                        statement.setObject(3, playerUuid);
                        statement.executeUpdate();
                    }
                })
                .thenCompose(ignored -> reload(islandId, playerUuid));
    }

    /** Number of members on an island (member-limit enforcement). */
    public CompletableFuture<Long> count(UUID islandId) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM island_members WHERE island_id = ?")) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getLong(1) : 0L;
                        }
                    }
                });
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
                        demote.executeUpdate();
                    }
                    try (PreparedStatement promote = connection.prepareStatement("UPDATE island_members SET is_owner = TRUE, role_id = NULL WHERE island_id = ? AND player_uuid = ?")) {
                        promote.setObject(1, islandId);
                        promote.setObject(2, newOwner);
                        promote.executeUpdate();
                    }
                })
                .thenApply(success -> {
                    invalidateGlobally(new MemberKey(islandId, oldOwner));
                    invalidateGlobally(new MemberKey(islandId, newOwner));
                    return success;
                });
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

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

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private CompletableFuture<IslandMember> reload(UUID islandId, UUID playerUuid) {
        MemberKey key = new MemberKey(islandId, playerUuid);
        invalidateGlobally(key);
        return findById(key);
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
