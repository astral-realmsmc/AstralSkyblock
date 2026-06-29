package com.astralrealms.skyblock.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.storage.model.EntityMetadata;
import com.astralrealms.core.storage.model.RowMapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectUpdatePacket;
import com.astralrealms.skyblock.model.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Per-island roles, keyed by their generated {@code BIGINT} id. {@link IslandRole} is not
 * {@code Unique}, so this talks to {@link com.astralrealms.core.storage.DatabaseService} directly,
 * reusing the framework {@link RowMapper} for row hydration.
 *
 * <p>The secondary index (island id → role ids) is maintained by the
 * {@link IndexedSyncedRepository} base on every cache entry path.
 */
public class RoleRepository extends IndexedSyncedRepository<Long, IslandRole, UUID> {

    private static final String COLUMNS = "id, island_id, kind, name, weight, is_default, created_at";

    private static final String INSERT_ROLE = """
            INSERT INTO island_roles (island_id, kind, name, weight, is_default)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final RowMapper<IslandRole> mapper = new RowMapper<>(EntityMetadata.of(IslandRole.class));

    public RoleRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ROLE_CACHE_KEY,
                ASConstants.ROLE_UPDATE_CHANNEL,
                IslandRole.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof LongObjectUpdatePacket updatePacket) {
                // The packet carries only the role id, so reload the role (it may be brand new here)
                // and take its owning island from the reloaded value, then rebuild that island's snapshot.
                cache.synchronous().refresh(updatePacket.id())
                        .thenAccept(role -> {
                            if (role != null)
                                this.plugin.islands().refreshRelationships(role.islandId());
                        });
            } else if (packet instanceof LongObjectDeletePacket deletePacket) {
                // Capture the owning island before evicting the role, then rebuild that island's snapshot.
                UUID islandId = findCachedById(deletePacket.id()).map(IslandRole::islandId).orElse(null);
                invalidateLocally(deletePacket.id());
                if (islandId != null)
                    this.plugin.islands().refreshRelationships(islandId);
            }
        });
    }

    @Unmodifiable
    public Collection<Long> getIslandRoleIds(UUID islandId) {
        return keysIn(islandId);
    }

    // =====================================================================================
    //  Domain queries
    // =====================================================================================

    /**
     * All of an island's roles, senior first (weight DESC, id). Primes the per-role cache.
     */
    public CompletableFuture<List<IslandRole>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /**
     * The island's default member role, or {@code null} if none is configured.
     */
    public CompletableFuture<IslandRole> findDefault(UUID islandId) {
        return findRoleId("SELECT id FROM island_roles WHERE default_guard = ?", islandId)
                .thenCompose(this::resolve);
    }

    /**
     * A system role of the island ({@code VISITOR}/{@code COOP}), resolved via {@code sys_kind}.
     */
    public CompletableFuture<IslandRole> findSystemRole(UUID islandId, IslandRole.Type type) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM island_roles WHERE island_id = ? AND sys_kind = ?")) {
                        statement.setObject(1, islandId);
                        statement.setInt(2, type.ordinal());
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getLong(1) : null;
                        }
                    }
                })
                .thenCompose(this::resolve);
    }

    /**
     * Creates a custom member role (kind=MEMBER) and returns it with its generated id.
     */
    public CompletableFuture<IslandRole> create(UUID islandId, String name, int weight) {
        return save(new IslandRole(null, islandId, IslandRole.Type.MEMBER, name, weight, false, 0L));
    }

    /**
     * Renames a role.
     */
    public CompletableFuture<Void> rename(long roleId, String name) {
        return update("UPDATE island_roles SET name = ? WHERE id = ?", name, roleId);
    }

    /**
     * Re-weights a role (higher = more senior).
     */
    public CompletableFuture<Void> setWeight(long roleId, int weight) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE island_roles SET weight = ? WHERE id = ?")) {
                        statement.setInt(1, weight);
                        statement.setLong(2, roleId);
                        statement.executeUpdate();
                    }
                })
                .thenRun(() -> invalidateGlobally(roleId));
    }

    /**
     * Switches the island's default member role (transactional). The current default is cleared
     * first because {@code uq_role_default} forbids two defaults existing at once.
     */
    public CompletableFuture<Boolean> setDefault(UUID islandId, long newRoleId) {
        return this.plugin.database()
                .transaction(connection -> {
                    try (PreparedStatement clear = connection.prepareStatement("UPDATE island_roles SET is_default = FALSE WHERE default_guard = ?")) {
                        clear.setObject(1, islandId);
                        clear.executeUpdate();
                    }
                    try (PreparedStatement set = connection.prepareStatement("UPDATE island_roles SET is_default = TRUE WHERE id = ?")) {
                        set.setLong(1, newRoleId);
                        set.executeUpdate();
                    }
                })
                .thenCompose(success -> {
                    // The previously-default role also changed; invalidate every cached role of the island.
                    List.copyOf(keysIn(islandId)).forEach(this::invalidateGlobally);
                    invalidateGlobally(newRoleId);
                    return this.plugin.islands().refreshRelationships(islandId)
                            .thenApply(ignored -> success);
                });
    }

    /**
     * Deletes a role (transactional), first reassigning its holders to {@code targetRoleId} so the
     * {@code island_members.role_id} RESTRICT constraint does not block the delete. The caller must
     * ensure the role is neither the default nor a system role.
     */
    public CompletableFuture<Boolean> delete(UUID islandId, long doomedRoleId, long targetRoleId) {
        return this.plugin.database()
                .transaction(connection -> {
                    try (PreparedStatement reassign = connection.prepareStatement("UPDATE island_members SET role_id = ? WHERE island_id = ? AND role_id = ?")) {
                        reassign.setLong(1, targetRoleId);
                        reassign.setObject(2, islandId);
                        reassign.setLong(3, doomedRoleId);
                        reassign.executeUpdate();
                    }
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM island_roles WHERE id = ?")) {
                        delete.setLong(1, doomedRoleId);
                        delete.executeUpdate();
                    }
                })
                .thenCompose(success -> {
                    invalidateGlobally(doomedRoleId);
                    return this.plugin.islands().refreshRelationships(islandId)
                            .thenApply(ignored -> success);
                });
    }

    /**
     * Number of members holding a role (e.g. before deleting it).
     */
    public CompletableFuture<Long> countHolders(UUID islandId, long roleId) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM island_members WHERE island_id = ? AND role_id = ?")) {
                        statement.setObject(1, islandId);
                        statement.setLong(2, roleId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getLong(1) : 0L;
                        }
                    }
                });
    }

    // =====================================================================================
    //  SyncedRepository contract
    // =====================================================================================

    @Override
    protected boolean sharedCacheEnabled() {
        return false; // roles are local-cache + database only
    }

    @Override
    protected Long keyFromValue(IslandRole value) {
        return value.id();
    }

    @Override
    protected String cacheKey(Long key) {
        return this.cacheKey + ":" + key;
    }

    @Override
    protected CompletableFuture<IslandRole> loadById(Long key) {
        String query = "SELECT " + COLUMNS + " FROM island_roles WHERE id = ?";
        return this.plugin.database()
                .supply(connection -> {
                    IslandRole role;
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setLong(1, key);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            role = resultSet.next() ? mapper.map(resultSet) : null;
                        }
                    }
                    if (role != null)
                        role.permissions(loadPermissions(connection, key));
                    return role;
                });
    }

    @Override
    protected CompletableFuture<IslandRole> saveToDatabase(IslandRole value) {
        return value.id() == null ? insert(value) : update(value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(Long key) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_roles WHERE id = ?")) {
                        statement.setLong(1, key);
                        statement.executeUpdate();
                    }
                });
    }

    @Override
    protected void publishUpdate(Long key, IslandRole value) {
        this.plugin.messaging().send(exchangeChannel, new LongObjectUpdatePacket(key));
    }

    @Override
    protected void publishInvalidation(Long key) {
        this.plugin.messaging().send(exchangeChannel, new LongObjectDeletePacket(key));
    }

    @Override
    protected UUID indexKeyOf(IslandRole value) {
        return value.islandId();
    }

    @Override
    protected CompletableFuture<List<IslandRole>> loadByIndex(UUID islandId) {
        String rolesQuery = "SELECT %s FROM island_roles WHERE island_id = ? ORDER BY weight DESC, id".formatted(COLUMNS);
        String permissionsQuery = """
                SELECT rp.role_id, rp.permission
                FROM island_role_permissions rp
                         JOIN island_roles r ON r.id = rp.role_id
                WHERE r.island_id = ?
                """;
        return this.plugin.database()
                .supply(connection -> {
                    List<IslandRole> roles = new ArrayList<>();
                    Map<Long, IslandRole> byId = new HashMap<>();
                    try (PreparedStatement statement = connection.prepareStatement(rolesQuery)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                IslandRole role = mapper.map(resultSet);
                                role.permissions(EnumSet.noneOf(IslandPermission.class));
                                roles.add(role);
                                byId.put(role.id(), role);
                            }
                        }
                    }
                    // One range scan for the whole island; fan the rows back out onto their roles.
                    try (PreparedStatement statement = connection.prepareStatement(permissionsQuery)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                IslandRole role = byId.get(resultSet.getLong(1));
                                if (role != null)
                                    parsePermission(resultSet.getString(2)).ifPresent(role.permissions()::add);
                            }
                        }
                    }
                    return roles;
                });
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private CompletableFuture<IslandRole> insert(IslandRole role) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(INSERT_ROLE, Statement.RETURN_GENERATED_KEYS)) {
                        bindRoleInsert(statement, role);
                        statement.executeUpdate();
                        IslandRole saved = withGeneratedId(statement, role);
                        saved.permissions(EnumSet.noneOf(IslandPermission.class));
                        return saved;
                    }
                })
                .thenCompose(saved -> {
                    index(saved);
                    return this.plugin.islands().refreshRelationships(saved.islandId())
                            .thenApply(ignored -> saved);
                });
    }

    private static void bindRoleInsert(PreparedStatement statement, IslandRole role) throws SQLException {
        statement.setObject(1, role.islandId());
        statement.setInt(2, role.kind().ordinal());
        statement.setString(3, role.name());
        statement.setInt(4, role.weight());
        statement.setBoolean(5, role.isDefault());
    }

    private static IslandRole withGeneratedId(Statement statement, IslandRole role) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next())
                throw new SQLException("No generated key returned for island_roles insert");
            return new IslandRole(
                    keys.getLong(1),
                    role.islandId(),
                    role.kind(),
                    role.name(),
                    role.weight(),
                    role.isDefault(),
                    role.createdAt()
            );
        }
    }

    /** Loads a single role's granted permissions, skipping any keys no longer backed by the enum. */
    private EnumSet<IslandPermission> loadPermissions(Connection connection, long roleId) throws SQLException {
        EnumSet<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
        try (PreparedStatement statement = connection.prepareStatement("SELECT permission FROM island_role_permissions WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next())
                    parsePermission(resultSet.getString(1)).ifPresent(permissions::add);
            }
        }
        return permissions;
    }

    /** Maps a stored permission key back to its enum, dropping (with a warning) any stale key. */
    private Optional<IslandPermission> parsePermission(String key) {
        if (key == null || key.isBlank())
            return Optional.empty();
        try {
            return Optional.of(IslandPermission.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            this.plugin.getSLF4JLogger().warn("Unknown island permission '{}' stored in island_role_permissions; ignoring.", key);
            return Optional.empty();
        }
    }

    private CompletableFuture<IslandRole> update(IslandRole role) {
        String query = "UPDATE island_roles SET name = ?, weight = ?, is_default = ? WHERE id = ?";
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setString(1, role.name());
                        statement.setInt(2, role.weight());
                        statement.setBoolean(3, role.isDefault());
                        statement.setLong(4, role.id());
                        statement.executeUpdate();
                    }
                })
                .thenCompose(ignored -> this.plugin.islands()
                        .refreshRelationships(role.islandId())
                        .thenApply(unused -> role));
    }

    private CompletableFuture<Void> update(String query, String value, long roleId) {
        return this.plugin.database()
                .run(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setString(1, value);
                        statement.setLong(2, roleId);
                        statement.executeUpdate();
                    }
                })
                .thenRun(() -> invalidateGlobally(roleId));
    }

    private CompletableFuture<Long> findRoleId(String query, UUID islandId) {
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? resultSet.getLong(1) : null;
                        }
                    }
                });
    }

    private CompletableFuture<IslandRole> resolve(Long roleId) {
        return roleId == null ? CompletableFuture.completedFuture(null) : findById(roleId);
    }
}
