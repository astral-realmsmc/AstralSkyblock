package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectUpdatePacket;
import com.astralrealms.skyblock.model.role.RolePermissions;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Role grant sets, keyed by role id. The cacheable unit is the whole {@link RolePermissions} set
 * of a role so the protection hot path can answer "does this role grant X?" from memory.
 *
 * <p>A role with no grants resolves to an empty (non-null) {@link RolePermissions}, so
 * {@link #findById(Object)} never returns {@code null} for a valid role id.
 */
public class PermissionRepository extends SyncedRepository<Long, RolePermissions> {

    public PermissionRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.PERMISSION_CACHE_KEY,
                ASConstants.PERMISSION_UPDATE_CHANNEL,
                RolePermissions.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof LongObjectUpdatePacket updatePacket)
                cache.synchronous().refresh(updatePacket.id());
            else if (packet instanceof LongObjectDeletePacket deletePacket)
                invalidateLocally(deletePacket.id());
        });
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /** The grant set of a role (empty if the role grants nothing). */
    public CompletableFuture<RolePermissions> findByRole(long roleId) {
        return findById(roleId);
    }

    /** Whether a role grants a permission, resolved against the cached grant set. */
    public CompletableFuture<Boolean> has(long roleId, String permission) {
        return findById(roleId).thenApply(grants -> grants != null && grants.has(permission));
    }

    /**
     * Loads every role grant set of an island in one query (the roles with no grants are absent),
     * priming the cache. Useful when hydrating the island aggregate.
     */
    public CompletableFuture<Map<Long, RolePermissions>> findByIsland(UUID islandId) {
        String sql = "SELECT rp.role_id, rp.permission FROM island_role_permissions rp "
                + "JOIN island_roles r ON r.id = rp.role_id WHERE r.island_id = ?";
        return this.plugin.database()
                .supply(connection -> {
                    Map<Long, Set<String>> grouped = new HashMap<>();
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next())
                                grouped.computeIfAbsent(resultSet.getLong(1), key -> new HashSet<>())
                                        .add(resultSet.getString(2));
                        }
                    }
                    Map<Long, RolePermissions> result = new HashMap<>();
                    grouped.forEach((roleId, permissions) -> result.put(roleId, new RolePermissions(roleId, permissions)));
                    return result;
                })
                .thenApply(result -> {
                    result.values().forEach(grants -> cache.synchronous().put(grants.roleId(), grants));
                    return result;
                });
    }

    /** Primes every role grant set of an island into L1 (lifecycle activation). */
    public CompletableFuture<Void> prime(UUID islandId) {
        return findByIsland(islandId).thenApply(ignored -> null);
    }

    /** Drops the given roles' grant sets from this server's L1 cache (lifecycle deactivation). */
    public void evict(Collection<Long> roleIds) {
        roleIds.forEach(this::invalidateLocally);
    }

    /** Grants a single permission to a role (idempotent). */
    public CompletableFuture<Void> grant(long roleId, String permission) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO island_role_permissions (role_id, permission) VALUES (?, ?)")) {
                        statement.setLong(1, roleId);
                        statement.setString(2, permission);
                        statement.executeUpdate();
                    }
                })
                .thenRun(() -> invalidateGlobally(roleId));
    }

    /** Revokes a single permission from a role. */
    public CompletableFuture<Void> revoke(long roleId, String permission) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_role_permissions WHERE role_id = ? AND permission = ?")) {
                        statement.setLong(1, roleId);
                        statement.setString(2, permission);
                        statement.executeUpdate();
                    }
                })
                .thenRun(() -> invalidateGlobally(roleId));
    }

    /** Replaces a role's entire grant set (transactional wipe + batch insert). */
    public CompletableFuture<RolePermissions> replace(long roleId, Set<String> permissions) {
        return save(new RolePermissions(roleId, permissions));
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected Long keyFromValue(RolePermissions value) {
        return value.roleId();
    }

    @Override
    protected String cacheKey(Long key) {
        return this.cacheKey + ":" + key;
    }

    @Override
    protected CompletableFuture<RolePermissions> loadById(Long key) {
        return this.plugin.database()
                .supply(connection -> {
                    Set<String> permissions = new HashSet<>();
                    try (PreparedStatement statement = connection.prepareStatement("SELECT permission FROM island_role_permissions WHERE role_id = ?")) {
                        statement.setLong(1, key);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next())
                                permissions.add(resultSet.getString(1));
                        }
                    }
                    return new RolePermissions(key, permissions);
                });
    }

    @Override
    protected CompletableFuture<RolePermissions> saveToDatabase(RolePermissions value) {
        return this.plugin.database()
                .transaction(connection -> {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM island_role_permissions WHERE role_id = ?")) {
                        delete.setLong(1, value.roleId());
                        delete.executeUpdate();
                    }
                    Set<String> permissions = value.permissions();
                    if (permissions != null && !permissions.isEmpty()) {
                        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO island_role_permissions (role_id, permission) VALUES (?, ?)")) {
                            for (String permission : permissions) {
                                insert.setLong(1, value.roleId());
                                insert.setString(2, permission);
                                insert.addBatch();
                            }
                            insert.executeBatch();
                        }
                    }
                })
                .thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(Long key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_role_permissions WHERE role_id = ?")) {
                        statement.setLong(1, key);
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected void publishUpdate(Long key, RolePermissions value) {
        this.plugin.messaging().send(exchangeChannel, new LongObjectUpdatePacket(key));
    }

    @Override
    protected void publishInvalidation(Long key) {
        this.plugin.messaging().send(exchangeChannel, new LongObjectDeletePacket(key));
    }
}
