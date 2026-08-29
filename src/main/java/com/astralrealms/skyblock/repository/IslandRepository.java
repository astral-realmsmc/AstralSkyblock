package com.astralrealms.skyblock.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.storage.pagination.Pageable;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.model.role.RoleSeed;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.*;

public class IslandRepository extends UUIDSyncedRepository<Island> {

    private final Map<String, UUID> nameIslandMap = new ConcurrentHashMap<>();
    // Reverse of nameIslandMap, so a rename can drop the island's previous name in O(1) rather than
    // walking an index that holds an entry per island on the network.
    private final Map<UUID, String> islandNameMap = new ConcurrentHashMap<>();
    private final CoopRepository coopRepository;

    public IslandRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ISLAND_CACHE_KEY,
                ASConstants.ISLAND_UPDATE_CHANNEL,
                Island.class
        );
        this.coopRepository = plugin.coops().repository();
    }

    @Override
    protected AsyncLoadingCache<UUID, Island> buildCache(AsyncCacheLoader<UUID, Island> cacheLoader) {
        return Caffeine.newBuilder()
                .maximumSize(250_000)
                .evictionListener((RemovalListener<UUID, Island>) (key, value, _) -> {
                    if (key != null) {
                        String indexed = islandNameMap.remove(key);
                        if (indexed != null)
                            nameIslandMap.remove(indexed, key);
                    }
                    if (value != null && value.name() != null)
                        nameIslandMap.remove(value.name(), key);
                })
                .buildAsync(cacheLoader);
    }

    @Override
    protected void cacheLocally(Island value) {
        super.cacheLocally(value);

        // A rename reaches this server as an update packet that refreshes the island in place, so the
        // previous name has to be retired here or it would keep resolving to this island forever.
        String previousName = value.name() == null
                ? this.islandNameMap.remove(value.uniqueId())
                : this.islandNameMap.put(value.uniqueId(), value.name());
        if (previousName != null && !previousName.equals(value.name()))
            this.nameIslandMap.remove(previousName, value.uniqueId());

        if (value.name() != null)
            this.nameIslandMap.put(value.name(), value.uniqueId());
    }

    /**
     * Keeps the name index in step with L1 evictions: when an island is dropped from the local cache
     * (delete, or a remote invalidation), its name entry is removed too.
     */
    @Override
    public @Nullable Island invalidateLocally(UUID key) {
        Island value = super.invalidateLocally(key);
        String indexed = this.islandNameMap.remove(key);
        if (indexed != null)
            this.nameIslandMap.remove(indexed, key);
        if (value != null && value.name() != null)
            this.nameIslandMap.remove(value.name(), key);
        return value;
    }

    /**
     * Cascade-loads an island's relationships onto it: its roles, its members (each resolved to the
     * role it holds), its owner, its coops, its bans, its warps, its settings and its upgrade
     * levels. Priming the per-island role, member, coop, ban, warp and upgrade slices is a side
     * effect. Used on every load (see {@link #postLoad}) and to refresh a cached island after a
     * membership/role change.
     *
     * <p>The loads are chained rather than run in parallel: warmup already cascades a whole page of
     * islands concurrently, and fanning every island out over six simultaneous queries would
     * multiply the pressure on the connection pool.
     */
    private CompletableFuture<Island> cascade(Island island) {
        UUID islandId = island.uniqueId();
        Cascaded cascaded = new Cascaded();
        return this.plugin.roles()
                .findByIsland(islandId)
                .thenCompose(roles -> {
                    cascaded.roles = roles;
                    return this.plugin.members().findByIsland(islandId);
                })
                .thenCompose(members -> {
                    cascaded.members = members;
                    return this.coopRepository.findByIsland(islandId);
                })
                .thenCompose(coops -> {
                    cascaded.coops = coops;
                    return this.plugin.bans().repository().findByIsland(islandId);
                })
                .thenCompose(bans -> {
                    cascaded.bans = bans;
                    return this.plugin.warps().repository().findByIsland(islandId);
                })
                .thenCompose(warps -> {
                    cascaded.warps = warps;
                    return this.findSettingsByIsland(islandId);
                })
                .thenCompose(settings -> {
                    cascaded.settings = settings;
                    return this.plugin.upgrades().findByIsland(islandId);
                })
                .thenApply(upgrades -> {
                    cascaded.upgrades = upgrades;
                    populate(island, cascaded);
                    return island;
                });
    }

    /** Mutable carrier for the results of the cascade chain. */
    private static final class Cascaded {
        private List<IslandRole> roles = List.of();
        private List<IslandMember> members = List.of();
        private List<IslandCoop> coops = List.of();
        private List<IslandBan> bans = List.of();
        private List<IslandWarp> warps = List.of();
        private EnumSet<IslandSettings> settings = EnumSet.noneOf(IslandSettings.class);
        private Map<UpgradeType, Integer> upgrades = Map.of();
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
                INSERT INTO islands (id, name, locked, level, value, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ISLAND)) {
            statement.setObject(1, island.uniqueId());
            statement.setString(2, island.name());
            statement.setBoolean(3, island.locked());
            statement.setLong(4, island.level());
            statement.setLong(5, island.value());
            statement.setDouble(6, island.spawnX());
            statement.setDouble(7, island.spawnY());
            statement.setDouble(8, island.spawnZ());
            statement.setFloat(9, island.spawnYaw());
            statement.setFloat(10, island.spawnPitch());
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

    /**
     * Rebuilds only a cached island's upgrade-level snapshot after an upgrade changed — an
     * upgrade touches no other relationship, so the full {@link #refreshRelationships(UUID)}
     * cascade is not needed. A no-op if the island is not cached on this server.
     */
    public CompletableFuture<Void> refreshUpgrades(UUID islandId) {
        Island island = findCachedById(islandId).orElse(null);
        if (island == null)
            return CompletableFuture.completedFuture(null);
        return this.plugin.upgrades()
                .findByIsland(islandId)
                .thenAccept(island::upgrades);
    }

    /**
     * Rebuilds only a cached island's ban snapshot after a ban changed. A no-op if the island is not
     * cached on this server.
     */
    public CompletableFuture<Void> refreshBans(UUID islandId) {
        Island island = findCachedById(islandId).orElse(null);
        if (island == null)
            return CompletableFuture.completedFuture(null);
        return this.plugin.bans()
                .repository()
                .findByIsland(islandId)
                .thenAccept(bans -> island.bans(new CopyOnWriteArrayList<>(bans)));
    }

    /**
     * Rebuilds only a cached island's warp snapshot after a warp changed. A no-op if the island is
     * not cached on this server.
     */
    public CompletableFuture<Void> refreshWarps(UUID islandId) {
        Island island = findCachedById(islandId).orElse(null);
        if (island == null)
            return CompletableFuture.completedFuture(null);
        return this.plugin.warps()
                .repository()
                .findByIsland(islandId)
                .thenAccept(warps -> island.warps(new CopyOnWriteArrayList<>(warps)));
    }

    private void populate(Island island, Cascaded cascaded) {
        Map<Long, IslandRole> rolesById = cascaded.roles.stream()
                .collect(Collectors.toMap(IslandRole::id, role -> role));

        IslandMember owner = null;
        for (IslandMember member : cascaded.members) {
            if (member.roleId() != null)
                member.role(rolesById.get(member.roleId()));
            if (member.isOwner())
                owner = member;
        }

        island.roles(cascaded.roles);
        island.members(cascaded.members);
        island.coops(new CopyOnWriteArrayList<>(cascaded.coops));
        island.bans(new CopyOnWriteArrayList<>(cascaded.bans));
        island.warps(new CopyOnWriteArrayList<>(cascaded.warps));
        island.owner(owner);
        island.settings(cascaded.settings);
        island.upgrades(cascaded.upgrades);
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

    /**
     * The highest-ranked islands, most valuable first. Reads ids straight from the indexed
     * {@code level} column and resolves them through the cache, so the leaderboard costs one small
     * query regardless of how many islands exist.
     */
    public CompletableFuture<List<Island>> findTop(int limit) {
        @Language("SQL") String query = """
                SELECT id FROM islands
                WHERE level > 0
                ORDER BY level DESC, updated_at ASC
                LIMIT ?
                """;
        return this.plugin.database()
                .supply(connection -> {
                    List<UUID> ids = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement(query)) {
                        statement.setInt(1, limit);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next())
                                ids.add(resultSet.getObject("id", UUID.class));
                        }
                    }
                    return ids;
                })
                .thenCompose(ids -> {
                    // Cache first: startup warms every island into L1, so the common refresh costs
                    // nothing beyond the query above. Only an island evicted since then is loaded,
                    // and a failed load drops that one row rather than voiding the whole board.
                    List<CompletableFuture<Island>> islands = ids.stream()
                            .map(id -> findCachedById(id)
                                    .map(CompletableFuture::completedFuture)
                                    .orElseGet(() -> findById(id).exceptionally(throwable -> {
                                        this.plugin.getSLF4JLogger().warn("Skipped island {} in the leaderboard: {}",
                                                id, throwable.getMessage());
                                        return null;
                                    })))
                            .toList();
                    return CompletableFuture.allOf(islands.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> islands.stream()
                                    .map(CompletableFuture::join)
                                    .filter(Objects::nonNull)
                                    .toList());
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
