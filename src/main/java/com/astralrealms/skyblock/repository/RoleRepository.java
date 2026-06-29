package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.storage.model.EntityMetadata;
import com.astralrealms.core.storage.model.RowMapper;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.LongObjectUpdatePacket;
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

    private final RowMapper<IslandRole> mapper = new RowMapper<>(EntityMetadata.of(IslandRole.class));

    public RoleRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ROLE_CACHE_KEY,
                ASConstants.ROLE_UPDATE_CHANNEL,
                IslandRole.class
        );
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof LongObjectUpdatePacket updatePacket)
                cache.synchronous().refresh(updatePacket.id());
            else if (packet instanceof LongObjectDeletePacket deletePacket)
                invalidateLocally(deletePacket.id());
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
                .thenApply(success -> {
                    // The previously-default role also changed; invalidate every cached role of the island.
                    List.copyOf(keysIn(islandId)).forEach(this::invalidateGlobally);
                    invalidateGlobally(newRoleId);
                    return success;
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
                .thenApply(success -> {
                    invalidateLocally(doomedRoleId);
                    invalidateGlobally(doomedRoleId);
                    return success;
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
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setLong(1, key);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            return resultSet.next() ? mapper.map(resultSet) : null;
                        }
                    }
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
        String query = "SELECT %s FROM island_roles WHERE island_id = ? ORDER BY weight DESC, id".formatted(COLUMNS);
        return this.plugin.database()
                .supply(connection -> {
                    List<IslandRole> roles = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, islandId);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next())
                                roles.add(mapper.map(resultSet));
                        }
                    }
                    return roles;
                });
    }

    // =====================================================================================
    //  Internals
    // =====================================================================================

    private CompletableFuture<IslandRole> insert(IslandRole role) {
        String query = """
                INSERT INTO island_roles (island_id, kind, name, weight, is_default)
                VALUES (?, ?, ?, ?, ?)
                """;
        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
                        statement.setObject(1, role.islandId());
                        statement.setInt(2, role.kind().ordinal());
                        statement.setString(3, role.name());
                        statement.setInt(4, role.weight());
                        statement.setBoolean(5, role.isDefault());
                        statement.executeUpdate();
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
                })
                .thenApply(saved -> {
                    index(saved);
                    return saved;
                });
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
                .thenApply(ignored -> role);
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
