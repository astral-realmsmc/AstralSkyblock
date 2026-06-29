package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.storage.pagination.Pageable;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.utils.ASConstants;
import com.github.benmanes.caffeine.cache.*;

public class IslandRepository extends UUIDSyncedRepository<Island> {

    private final Map<String, UUID> nameIslandMap = new ConcurrentHashMap<>();

    public IslandRepository(AstralSkyblock plugin) {
        super(
                plugin,
                ASConstants.ISLAND_CACHE_KEY,
                ASConstants.ISLAND_UPDATE_CHANNEL,
                Island.class
        );
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
                        .thenApply(members -> {
                            populate(island, roles, members);
                            return island;
                        }));
    }

    @Override
    protected CompletableFuture<Island> postLoad(Island island) {
        return cascade(island);
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

    static void populate(Island island, List<IslandRole> roles, List<IslandMember> members) {
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
        island.owner(owner);
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
