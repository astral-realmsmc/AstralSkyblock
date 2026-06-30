package com.astralrealms.skyblock.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.storage.pagination.Pageable;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.model.role.RoleSeed;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.*;

public class IslandRepository extends UUIDSyncedRepository<Island> {

    private final Map<String, UUID> nameIslandMap = new ConcurrentHashMap<>();
    private final CoopRepository coopRepository;

    public IslandRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ISLAND_CACHE_KEY,
                ASConstants.ISLAND_UPDATE_CHANNEL,
                Island.class
        );
        this.coopRepository = new CoopRepository(plugin);
    }

    @Override
    protected AsyncLoadingCache<UUID, Island> buildCache(AsyncCacheLoader<UUID, Island> cacheLoader) {
        return Caffeine.newBuilder()
                .maximumSize(250_000)
                .evictionListener((RemovalListener<UUID, Island>) (key, value, _) -> {
                    if (value != null && value.name() != null)
                        nameIslandMap.remove(value.name(), key);
                    else if (key != null)
                        nameIslandMap.values().remove(key);
                })
                .buildAsync(cacheLoader);
    }

    @Override
    protected void cacheLocally(Island value) {
        super.cacheLocally(value);
        if (value.name() != null)
            this.nameIslandMap.put(value.name(), value.uniqueId());
    }

    /**
     * Keeps the name index in step with L1 evictions: when an island is dropped from the local cache
     * (delete, or a remote invalidation), its name entry is removed too. A rename leaves the previous
     * name pointing here until the next invalidation, so it is matched and cleared by value as a fallback.
     */
    @Override
    public @Nullable Island invalidateLocally(UUID key) {
        Island value = super.invalidateLocally(key);
        if (value != null && value.name() != null)
            this.nameIslandMap.remove(value.name(), key);
        else
            this.nameIslandMap.values().remove(key);
        return value;
    }

    /**
     * Cascade-loads an island's relationships onto it: its roles, its members (each resolved to the
     * role it holds), and its owner. Settings and per-role permissions are deferred. Priming the
     * per-island role and member slices is a side effect. Used on every load (see {@link #postLoad})
     * and to refresh a cached island after a membership/role change.
     */
    private CompletableFuture<Island> cascade(Island island) {
        UUID islandId = island.uniqueId();
        return this.plugin.roles()
                .findByIsland(islandId)
                .thenCompose(roles -> this.plugin.members().findByIsland(islandId)
                        .thenCompose(members -> this.coopRepository.findByIsland(islandId)
                                .thenCompose(coops -> this.findSettingsByIsland(islandId)
                                        .thenApply(settings -> {
                                            populate(island, roles, members, coops, settings);
                                            return island;
                                        }))));
    }

    @Override
    protected CompletableFuture<Island> postLoad(Island island) {
        return cascade(island);
    }

    /**
     * Creates an island and its entire initial aggregate — the island row, its configured roles each
     * with their seeded permission grants, and the structural owner member — in a single transaction.
     * Either everything commits or nothing does. On success the island is cascaded (priming the role
     * and member slices from the just-committed rows), written through to L1/L2, and published so other
     * servers pick it up.
     */
    public CompletableFuture<Island> create(Island island, List<RoleSeed> roleSeeds, UUID ownerUuid) {
        return this.plugin.database()
                .transactionSupply(connection -> {
                    insertIsland(connection, island);
                    for (RoleSeed seed : roleSeeds) {
                        long roleId = insertRole(connection, seed.role());
                        insertPermissions(connection, roleId, seed.permissions());
                    }
                    insertOwner(connection, island.uniqueId(), ownerUuid);
                    Set<IslandSettings> defaultSettings = this.plugin.configuration().defaultSettings();
                    for (IslandSettings value : IslandSettings.values()) {
                        insertSettings(connection, island.uniqueId(), value, defaultSettings.contains(value));
                    }
                    return island;
                })
                .thenCompose(saved -> cache(saved).thenApply(ignored -> saved))
                .thenCompose(this::cascade)
                .thenApply(saved -> {
                    publishUpdate(saved.uniqueId(), saved);
                    return saved;
                });
    }

    private void insertIsland(Connection connection, Island island) throws SQLException {
        @Language("SQL") String INSERT_ISLAND = """
                INSERT INTO islands (id, name, locked, level, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ISLAND)) {
            statement.setObject(1, island.uniqueId());
            statement.setString(2, island.name());
            statement.setBoolean(3, island.locked());
            statement.setInt(4, island.level());
            statement.setDouble(5, island.spawnX());
            statement.setDouble(6, island.spawnY());
            statement.setDouble(7, island.spawnZ());
            statement.setFloat(8, island.spawnYaw());
            statement.setFloat(9, island.spawnPitch());
            statement.executeUpdate();
        }
    }

    private long insertRole(Connection connection, IslandRole role) throws SQLException {
        @Language("SQL") String INSERT_ROLE = """
                INSERT INTO island_roles (island_id, kind, name, weight, is_default)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ROLE, Statement.RETURN_GENERATED_KEYS)) {
            statement.setObject(1, role.islandId());
            statement.setInt(2, role.kind().ordinal());
            statement.setString(3, role.name());
            statement.setInt(4, role.weight());
            statement.setBoolean(5, role.isDefault());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next())
                    throw new SQLException("No generated key returned for island_roles insert");
                return keys.getLong(1);
            }
        }
    }

    private void insertPermissions(Connection connection, long roleId, Set<IslandPermission> permissions) throws SQLException {
        if (permissions.isEmpty())
            return;

        @Language("SQL") String INSERT_PERMISSION = """
                INSERT INTO island_role_permissions (role_id, permission)
                VALUES (?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(INSERT_PERMISSION)) {
            for (IslandPermission permission : permissions) {
                statement.setLong(1, roleId);
                statement.setString(2, permission.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertOwner(Connection connection, UUID islandId, UUID ownerUuid) throws SQLException {
        @Language("SQL") String INSERT_OWNER = """
                INSERT INTO island_members (island_id, player_uuid, is_owner, role_id)
                VALUES (?, ?, TRUE, NULL)
                """;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OWNER)) {
            statement.setObject(1, islandId);
            statement.setObject(2, ownerUuid);
            statement.executeUpdate();
        }
    }

    private void insertSettings(Connection connection, UUID islandId, IslandSettings setting, boolean value) throws SQLException {
        @Language("SQL") String INSERT_SETTING = """
                INSERT INTO island_flags (island_id, flag, allowed)
                VALUES (?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SETTING)) {
            statement.setObject(1, islandId);
            statement.setString(2, setting.name());
            statement.setBoolean(3, value);
            statement.executeUpdate();
        }
    }

    public CompletableFuture<Boolean> updateSettings(UUID islandId, Map<IslandSettings, Boolean> settings) {
        @Language("SQL") String UPDATE_SETTING = """
                INSERT INTO island_flags (island_id, flag, allowed)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE allowed = VALUES(allowed)
                """;

        return this.plugin.database()
                .transactionSupply(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SETTING)) {
                        for (Map.Entry<IslandSettings, Boolean> entry : settings.entrySet()) {
                            statement.setObject(1, islandId);
                            statement.setString(2, entry.getKey().name());
                            statement.setBoolean(3, entry.getValue());
                            statement.addBatch();
                        }
                        int[] results = statement.executeBatch();
                        for (int result : results) {
                            if (result == Statement.EXECUTE_FAILED) {
                                throw new SQLException("Failed to update island settings");
                            }
                        }
                        return true;
                    }
                });
    }

    public CompletableFuture<EnumSet<IslandSettings>> findSettingsByIsland(UUID islandId) {
        @Language("SQL") String query = """
                SELECT flag, allowed
                FROM island_flags
                WHERE island_id = ?
                """;

        return this.plugin.database()
                .supply(connection -> {
                    EnumSet<IslandSettings> settings = EnumSet.noneOf(IslandSettings.class);
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setObject(1, islandId);
                        try (ResultSet rs = statement.executeQuery()) {
                            while (rs.next()) {
                                String flagName = rs.getString("flag");
                                boolean allowed = rs.getBoolean("allowed");
                                IslandSettings setting = IslandSettings.valueOf(flagName);
                                if (allowed)
                                    settings.add(setting);
                            }
                        }
                    }
                    return settings;
                });
    }

    /**
     * Re-cascades a cached island's relationships after its membership or roles changed. A no-op if
     * the island is not cached on this server.
     */
    public CompletableFuture<Void> refreshRelationships(UUID islandId) {
        Island island = findCachedById(islandId).orElse(null);
        if (island == null)
            return CompletableFuture.completedFuture(null);
        return cascade(island).thenAccept(ignored -> {
        });
    }

    private void populate(Island island, List<IslandRole> roles, List<IslandMember> members,
                          List<IslandCoop> coops, EnumSet<IslandSettings> settings) {
        Map<Long, IslandRole> rolesById = roles.stream()
                .collect(Collectors.toMap(IslandRole::id, role -> role));

        IslandMember owner = null;
        for (IslandMember member : members) {
            if (member.roleId() != null)
                member.role(rolesById.get(member.roleId()));
            if (member.isOwner())
                owner = member;
        }

        island.roles(roles);
        island.members(members);
        island.coops(new ArrayList<>(coops));
        island.owner(owner);
        island.settings(settings);
    }

    /**
     * Loads every island — fully cascaded with its relationships — into the local L1 cache (and name
     * index) in pages of {@link ASConstants#ISLAND_WARMUP_PAGE_SIZE}, one page at a time, so a large
     * island table never has to be held in a single result set. Intended to be called once on startup.
     */
    public CompletableFuture<Void> warmup() {
        return warmupPage(0);
    }

    private CompletableFuture<Void> warmupPage(int page) {
        Pageable pageable = Pageable.of(page, ASConstants.ISLAND_WARMUP_PAGE_SIZE, "id");
        return this.repository.findAll(pageable)
                .thenCompose(result -> {
                    CompletableFuture<?>[] tasks = result.content().stream()
                            .map(island -> cascade(island).thenAccept(this::cacheLocally))
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(tasks)
                            .thenCompose(ignored -> result.hasNext()
                                    ? warmupPage(page + 1)
                                    : CompletableFuture.completedFuture(null));
                });
    }

    public Optional<Island> findByName(String name) {
        UUID islandId = this.nameIslandMap.get(name);
        if (islandId == null)
            return Optional.empty();
        return findCachedById(islandId);
    }

    public CompletableFuture<Boolean> existsByName(String name) {
        UUID islandId = this.nameIslandMap.get(name);
        if (islandId != null)
            return CompletableFuture.completedFuture(true);
        @Language("SQL") String query = """
                SELECT 1 FROM islands
                WHERE name = ?
                LIMIT 1
                """;

        return this.plugin.database()
                .supply(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(query)) {
                        stmt.setString(1, name);
                        try (ResultSet rs = stmt.executeQuery()) {
                            return rs.next();
                        }
                    }
                });
    }

    @Unmodifiable
    public Collection<String> names() {
        return this.nameIslandMap.keySet();
    }
}
