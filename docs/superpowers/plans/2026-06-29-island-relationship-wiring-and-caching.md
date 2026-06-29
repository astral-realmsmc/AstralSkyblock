# Island Relationship Wiring & Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every island/member/role/warp/upgrade/flag/ban/coop relationship resolvable synchronously from a fully-primed in-memory cache, with priming tied to island activation and zero database/Redis access on the read hot path.

**Architecture:** Introduce an `IndexedSyncedRepository<K,V,I>` base that maintains a per-island secondary index on *every* L1 cache path (loader, `refresh()`, write-through, bulk prime) — fixing the existing path-dependent index bug by construction. Migrate `MemberRepository`/`RoleRepository` onto it and build five new repositories (warp/upgrade/flag/ban/coop) on it. An `IslandContextService` bulk-primes all relations when an island's world loads locally and evicts them on unload. Model classes gain thin synchronous accessors that read the indexes.

**Tech Stack:** Java 25, Maven, Caffeine (async loading cache), Guava `Multimap`, Lombok (**fluent accessors** — getters are `field()` not `getField()`), JUnit 5 + Mockito + AssertJ (added by this plan), MySQL via the core `DatabaseService`, Redis via the core `CacheService`, pub/sub via the core `MessagingService`.

## Global Constraints

- Java release: **25** (`<source>/<target>` already set; do not change).
- Lombok accessors are **fluent**: generate/call `islandId()`, `name()`, `isOwner()` — never `getIslandId()`.
- All cross-server cache classes extend `SyncedRepository<K,V>`; never bypass it for ad-hoc caching.
- Read accessors on model classes must be **synchronous**, **non-blocking**, **local-only**: read L1 + indexes via `AstralSkyblock.get()`, return empty/`null` when the island is not active on this server. Never call DB/Redis from an accessor.
- All mutating repository operations return `CompletableFuture<…>`.
- Cache key convention: `skyblock:<entity>` (+ `:` + key parts). Channel convention: `skyblock.<entity>.update`.
- New cross-server packets are registered in `ASPacketRegistry` at the next free repository id (`0x06+`).
- Secondary-index maintenance must never assume `cacheLocally()` is the only insertion path.
- Priming reads DB → L1 directly (via `cacheLocally`) and must **not** re-publish every entry into L2/Redis (matches existing warmup convention).
- Commit after every task with the message shown in its final step.

---

## File Structure

**New production files**
- `repository/IndexedSyncedRepository.java` — shared indexed cache base.
- `model/island/WarpKey.java`, `model/island/UpgradeKey.java`, `model/island/FlagKey.java`, `model/member/IslandPlayerKey.java` — composite cache keys (records).
- `messaging/packet/repository/IslandStringKeyUpdatePacket.java`, `IslandStringKeyDeletePacket.java`, `IslandPlayerKeyUpdatePacket.java`, `IslandPlayerKeyDeletePacket.java` — generic cross-server invalidation packets.
- `repository/WarpRepository.java`, `UpgradeRepository.java`, `FlagRepository.java`, `BanRepository.java`, `CoopRepository.java`.
- `service/WarpService.java`, `UpgradeService.java`, `FlagService.java`, `BanService.java`, `CoopService.java`.
- `service/IslandContextService.java` — activation/deactivation orchestrator.

**Modified production files**
- `repository/MemberRepository.java`, `repository/RoleRepository.java` — migrate to base.
- `repository/PermissionRepository.java` — add island-scoped `prime`/`evict` helpers.
- `model/island/Island.java`, `model/member/IslandMember.java`, `model/role/IslandRole.java`, `model/member/SkyblockPlayer.java` — relationship accessors.
- `service/MemberService.java` — align to base method names.
- `service/WorldService.java`, `service/IslandService.java` — activation hooks.
- `listener/PlayerConnectionListener.java` — prime player's own membership.
- `messaging/ASPacketRegistry.java`, `utils/ASConstants.java`, `AstralSkyblock.java`, `pom.xml`.

**New test files** (under `src/test/java/...` mirroring packages)
- `support/PluginTestSupport.java` — Mockito harness building a stubbed `AstralSkyblock`.
- `repository/IndexedSyncedRepositoryTest.java`, `repository/MemberRepositoryTest.java`, `repository/RoleRepositoryTest.java`, `repository/WarpRepositoryTest.java` (template; ban/upgrade/flag/coop get one mapping test each), `service/IslandContextServiceTest.java`, plus the per-task tests below.

---

### Task 1: Test harness & build setup

**Files:**
- Modify: `pom.xml` (add test-scope dependencies + surefire arg)
- Create: `src/test/java/com/astralrealms/skyblock/support/PluginTestSupport.java`
- Test: `src/test/java/com/astralrealms/skyblock/support/PluginTestSupportTest.java`

**Interfaces:**
- Produces: `PluginTestSupport.mockPlugin()` → a Mockito-mocked `AstralSkyblock` whose `cache()` returns a `CacheService` stubbed for L2 misses (`get` → `null`, `set`/`del` → completed) and whose `messaging()` returns a no-op `MessagingService`. `PluginTestSupport.database(plugin)` → the mocked `DatabaseService` for per-test stubbing.

- [ ] **Step 1: Add test dependencies to `pom.xml`**

Insert these dependencies at the end of the `<dependencies>` block (immediately before `</dependencies>` on line 140):

```xml
        <!-- testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>3.26.3</version>
            <scope>test</scope>
        </dependency>
```

Then replace the surefire plugin block (lines 179-184) with one that enables the ByteBuddy experimental flag (required to mock final classes on the newest JDKs) and the Mockito inline agent:

```xml
            <!-- surefire (run JUnit 5) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <argLine>-Dnet.bytebuddy.experimental=true</argLine>
                </configuration>
            </plugin>
```

- [ ] **Step 2: Write the harness**

Create `src/test/java/com/astralrealms/skyblock/support/PluginTestSupport.java`:

```java
package com.astralrealms.skyblock.support;

import java.util.concurrent.CompletableFuture;

import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Builds a Mockito-stubbed {@link AstralSkyblock} suitable for unit-testing repositories without a
 * real database, Redis, or messaging backend. L2 (Redis) reads always miss, so the loader falls
 * through to {@code loadById}; L2 writes and message sends are no-ops.
 */
public final class PluginTestSupport {

    private PluginTestSupport() {
    }

    public static AstralSkyblock mockPlugin() {
        AstralSkyblock plugin = mock(AstralSkyblock.class);

        CacheService cache = mock(CacheService.class);
        lenient().when(cache.get(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(cache.set(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("OK"));
        lenient().when(cache.set(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture("OK"));
        lenient().when(cache.del(anyString())).thenReturn(CompletableFuture.completedFuture(1L));

        MessagingService messaging = mock(MessagingService.class);

        DatabaseService database = mock(DatabaseService.class);

        lenient().when(plugin.cache()).thenReturn(cache);
        lenient().when(plugin.messaging()).thenReturn(messaging);
        lenient().when(plugin.database()).thenReturn(database);
        return plugin;
    }

    public static DatabaseService database(AstralSkyblock plugin) {
        return plugin.database();
    }
}
```

> If the core `CacheService.del` return type is not `CompletableFuture<Long>`, adjust the `del` stub's completed value to match (the value is only consumed by an `.exceptionally` fallback, so any non-null works). Confirm the three core class FQNs (`com.astralrealms.core.cache.CacheService`, `com.astralrealms.core.messaging.MessagingService`, `com.astralrealms.core.storage.DatabaseService`) against `AstralSkyblock.java`'s imports and fix if the packages differ.

- [ ] **Step 3: Write the harness sanity test**

Create `src/test/java/com/astralrealms/skyblock/support/PluginTestSupportTest.java`:

```java
package com.astralrealms.skyblock.support;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;

import static org.assertj.core.api.Assertions.assertThat;

class PluginTestSupportTest {

    @Test
    void mockPluginExposesStubbedCollaborators() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        assertThat(plugin.cache()).isNotNull();
        assertThat(plugin.messaging()).isNotNull();
        assertThat(plugin.database()).isNotNull();
        assertThat(plugin.cache().get("anything").join()).isNull();
    }
}
```

- [ ] **Step 4: Run the test (expect PASS once deps resolve)**

Run: `mvn -q test -Dtest=PluginTestSupportTest`
Expected: BUILD SUCCESS, 1 test passing. If it fails to compile on the core class imports, fix the FQNs per the Step 2 note and re-run.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test/java/com/astralrealms/skyblock/support/PluginTestSupport.java src/test/java/com/astralrealms/skyblock/support/PluginTestSupportTest.java
git commit -m "test: add JUnit5/Mockito/AssertJ harness for repository tests"
```

---

### Task 2: `IndexedSyncedRepository` base

**Files:**
- Create: `src/main/java/com/astralrealms/skyblock/repository/IndexedSyncedRepository.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/IndexedSyncedRepositoryTest.java`

**Interfaces:**
- Consumes: `SyncedRepository<K,V>` (existing), `PluginTestSupport` (Task 1).
- Produces:
  - `abstract I indexKeyOf(V value)`
  - `abstract CompletableFuture<List<V>> loadByIndex(I indexKey)`
  - `Collection<K> keysIn(I indexKey)` (unmodifiable snapshot)
  - `CompletableFuture<List<V>> prime(I indexKey)` (bulk load → atomic index slice replace → L1 populate)
  - `void evictIndex(I indexKey)` (L1-only eviction + slice clear)
  - protected hooks `index(V)`, `deindex(K, V)`; overrides of `buildCache`, `cacheLocally`, `invalidateLocally`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/IndexedSyncedRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class IndexedSyncedRepositoryTest {

    /** A row with its own id (K=UUID) grouped under an island id (I=UUID). */
    record Row(UUID id, UUID islandId) {
    }

    /** In-memory concrete repository exercising the base's index behavior without a real DB. */
    static class TestRepo extends IndexedSyncedRepository<UUID, Row, UUID> {
        final Map<UUID, Row> table = new ConcurrentHashMap<>();

        TestRepo(AstralSkyblock plugin) {
            super(plugin, "skyblock:test", "skyblock.test.update", Row.class);
        }

        @Override protected UUID indexKeyOf(Row v) { return v.islandId(); }
        @Override protected UUID keyFromValue(Row v) { return v.id(); }
        @Override protected String cacheKey(UUID key) { return this.cacheKey + ":" + key; }

        @Override protected CompletableFuture<Row> loadById(UUID key) {
            return CompletableFuture.completedFuture(table.get(key));
        }
        @Override protected CompletableFuture<List<Row>> loadByIndex(UUID islandId) {
            return CompletableFuture.completedFuture(
                    table.values().stream().filter(r -> r.islandId().equals(islandId)).toList());
        }
        @Override protected CompletableFuture<Row> saveToDatabase(Row v) {
            table.put(v.id(), v);
            return CompletableFuture.completedFuture(v);
        }
        @Override protected CompletableFuture<Void> deleteFromDatabase(UUID key) {
            table.remove(key);
            return CompletableFuture.completedFuture(null);
        }
        @Override protected void publishUpdate(UUID key, Row v) { }
        @Override protected void publishInvalidation(UUID key) { }
    }

    AstralSkyblock plugin;
    TestRepo repo;
    UUID island;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        repo = new TestRepo(plugin);
        island = UUID.randomUUID();
    }

    @Test
    void loaderPathPopulatesTheIndex() {
        UUID id = UUID.randomUUID();
        repo.table.put(id, new Row(id, island));

        repo.findById(id).join(); // read-through loader path (bypasses cacheLocally)

        assertThat(repo.keysIn(island)).containsExactly(id);
    }

    @Test
    void primeReplacesSliceAtomicallyAndPopulatesL1() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.table.put(b, new Row(b, island));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactlyInAnyOrder(a, b);
        assertThat(repo.findCachedById(a)).isPresent();
        assertThat(repo.findCachedById(b)).isPresent();
    }

    @Test
    void evictIndexClearsSliceAndL1() {
        UUID a = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.prime(island).join();

        repo.evictIndex(island);

        assertThat(repo.keysIn(island)).isEmpty();
        assertThat(repo.findCachedById(a)).isEmpty();
    }

    @Test
    void invalidateRemovesKeyFromIndex() {
        UUID a = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.prime(island).join();

        repo.invalidateLocally(a);

        assertThat(repo.keysIn(island)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile (class missing)**

Run: `mvn -q test -Dtest=IndexedSyncedRepositoryTest`
Expected: compilation failure — `IndexedSyncedRepository` does not exist.

- [ ] **Step 3: Implement the base class**

Create `src/main/java/com/astralrealms/skyblock/repository/IndexedSyncedRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

/**
 * A {@link SyncedRepository} that additionally keeps a single secondary index mapping an index key
 * {@code I} (here always an island id) to the cache keys {@code K} of the values that belong to it.
 *
 * <p>The index is maintained on <b>every</b> path that inserts a value into L1 — the async loader
 * (read-through and {@code refresh()}), the write-through {@link #cacheLocally(Object)}, and the
 * bulk {@link #prime(Object)} — and is cleared on eviction and {@link #invalidateLocally(Object)}.
 * This is the key property the relationship accessors rely on: an active island's slice is complete
 * after {@link #prime(Object)} and stays correct as individual entries load, refresh, or invalidate.
 */
public abstract class IndexedSyncedRepository<K, V, I> extends SyncedRepository<K, V> {

    private final Multimap<I, K> index = Multimaps.synchronizedMultimap(HashMultimap.create());

    public IndexedSyncedRepository(AstralSkyblock plugin, String cacheKey, String exchangeChannel, Class<V> valueClass) {
        super(plugin, cacheKey, exchangeChannel, valueClass);
    }

    /** Extracts the index key (island id) from a value. */
    protected abstract I indexKeyOf(V value);

    /** Bulk-loads every value under an index key from the database. */
    protected abstract CompletableFuture<List<V>> loadByIndex(I indexKey);

    @Override
    protected AsyncLoadingCache<K, V> buildCache(AsyncCacheLoader<K, V> cacheLoader) {
        return Caffeine.newBuilder()
                .recordStats()
                .evictionListener((RemovalListener<K, V>) (key, value, _) -> {
                    if (key != null)
                        deindex(key, value);
                })
                // Wrap the loader so a value entering L1 via read-through or refresh() is indexed too.
                .buildAsync((key, executor) -> cacheLoader.asyncLoad(key, executor)
                        .thenApply(value -> {
                            if (value != null)
                                index(value);
                            return value;
                        }));
    }

    @Override
    protected void cacheLocally(V value) {
        super.cacheLocally(value);
        index(value);
    }

    @Override
    public @Nullable V invalidateLocally(K key) {
        V value = super.invalidateLocally(key);
        deindex(key, value);
        return value;
    }

    /** Adds {@code value}'s key to its index slice. Idempotent. */
    protected void index(V value) {
        this.index.put(indexKeyOf(value), keyFromValue(value));
    }

    /** Removes {@code key} from {@code value}'s slice (best-effort if {@code value} is null). */
    protected void deindex(K key, @Nullable V value) {
        if (value != null)
            this.index.remove(indexKeyOf(value), key);
        else
            this.index.values().remove(key);
    }

    /** Snapshot of the cache keys currently indexed under {@code indexKey}. */
    @Unmodifiable
    public Collection<K> keysIn(I indexKey) {
        return List.copyOf(this.index.get(indexKey));
    }

    /**
     * Bulk-loads every value under {@code indexKey}, atomically replacing that index slice and
     * populating L1. This is the lifecycle prime primitive; it does not write to L2 (Redis).
     */
    public CompletableFuture<List<V>> prime(I indexKey) {
        return loadByIndex(indexKey)
                .thenApply(values -> {
                    this.index.replaceValues(indexKey, values.stream().map(this::keyFromValue).toList());
                    values.forEach(value -> cache.synchronous().put(keyFromValue(value), value));
                    onPrimed(indexKey, values);
                    return values;
                });
    }

    /**
     * Hook for subclasses maintaining additional indexes (e.g. a player→island index) to react to a
     * completed prime. Default: no-op. Called after the primary slice and L1 are populated.
     */
    protected void onPrimed(I indexKey, List<V> values) {
    }

    /**
     * Drops every value under {@code indexKey} from this server's L1 cache (L2 stays warm) and clears
     * the slice. Used when an island deactivates locally.
     */
    public void evictIndex(I indexKey) {
        List.copyOf(this.index.get(indexKey)).forEach(this::invalidateLocally);
        this.index.removeAll(indexKey);
    }
}
```

> Note: `prime` populates L1 with raw `cache.synchronous().put` and sets the index via `replaceValues` (so stale keys are dropped). It deliberately does **not** route through `cacheLocally`, so subclasses with extra indexes use the `onPrimed` hook (see Task 3).

- [ ] **Step 4: Run the tests (expect PASS)**

Run: `mvn -q test -Dtest=IndexedSyncedRepositoryTest`
Expected: BUILD SUCCESS, 4 tests passing. The `loaderPathPopulatesTheIndex` test is the regression guard for the original bug.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/IndexedSyncedRepository.java src/test/java/com/astralrealms/skyblock/repository/IndexedSyncedRepositoryTest.java
git commit -m "feat: add IndexedSyncedRepository base maintaining index on all cache paths"
```

---

### Task 3: Migrate `MemberRepository` onto the base

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/repository/MemberRepository.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/MemberRepositoryTest.java`

**Interfaces:**
- Consumes: `IndexedSyncedRepository<MemberKey, IslandMember, UUID>`.
- Produces (unchanged public surface where possible): `keysIn(islandId)` replaces `findIslandMembers`'s backing; `findPlayerIslands(UUID)` retained (player→island index); `findByIsland(UUID)` delegates to `prime`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/MemberRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MemberRepositoryTest {

    AstralSkyblock plugin;
    DatabaseService database;
    MemberRepository repo;
    UUID island;
    UUID player;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        database = plugin.database();
        repo = new MemberRepository(plugin);
        island = UUID.randomUUID();
        player = UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void primePopulatesBothIslandAndPlayerIndexes() {
        IslandMember member = new IslandMember(island, player, true, null, 0L);
        // loadByIndex runs database.supply(...); ignore the lambda and return the canned list.
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(member)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new MemberKey(island, player));
        assertThat(repo.findPlayerIslands(player)).containsExactly(island);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=MemberRepositoryTest`
Expected: FAIL — `keysIn`/`findPlayerIslands` not wired to the base, or `prime`/`findByIsland` not present. (May be a compile error until Step 3.)

- [ ] **Step 3: Rewrite `MemberRepository` to extend the base**

Replace the class declaration, the index fields, `buildCache`, the `findIslandMembers`/`findPlayerIslands` accessors, `findByIsland`, `cacheLocally`, and add `indexKeyOf`/`loadByIndex`/`onPrimed`. Concretely:

Change the class header (line 34) from:

```java
public class MemberRepository extends SyncedRepository<MemberKey, IslandMember> {
```

to:

```java
public class MemberRepository extends IndexedSyncedRepository<MemberKey, IslandMember, UUID> {
```

Remove the `islandMembersMap` field (line 37) and keep only the player index:

```java
    private final Multimap<UUID, UUID> playerIslandsMap = Multimaps.synchronizedMultimap(HashMultimap.create());
```

Delete the `buildCache` override (lines 57-67) entirely — the base provides it. Replace the two accessor methods (lines 69-77) with:

```java
    @Unmodifiable
    public Collection<UUID> findIslandMembers(UUID islandId) {
        return keysIn(islandId).stream().map(MemberKey::playerUuid).toList();
    }

    @Unmodifiable
    public Collection<UUID> findPlayerIslands(UUID playerUuid) {
        return List.copyOf(playerIslandsMap.get(playerUuid));
    }
```

Replace `findByIsland` (lines 86-104) with a delegation to `prime`:

```java
    /** Every member of an island. Primes the per-member cache and indexes. */
    public CompletableFuture<List<IslandMember>> findByIsland(UUID islandId) {
        return prime(islandId);
    }
```

Replace the `cacheLocally` override (lines 318-324) and add the base hooks:

```java
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
    protected void onPrimed(UUID islandId, List<IslandMember> values) {
        values.forEach(member -> this.playerIslandsMap.put(member.playerUuid(), member.islandId()));
    }
```

Delete the old `cacheLocally` override that maintained `islandMembersMap`/`playerIslandsMap` (the base + the `index`/`deindex`/`onPrimed` overrides above replace it). Keep `keyFromValue`, `cacheKey`, `loadById`, `saveToDatabase`, `deleteFromDatabase`, `publishUpdate`, `publishInvalidation`, and all domain mutators unchanged. Ensure imports still include `HashMultimap`, `Multimap`, `Multimaps`, `ArrayList`, `List`, `Collection`, `Unmodifiable`.

- [ ] **Step 4: Run the tests (expect PASS)**

Run: `mvn -q test -Dtest=MemberRepositoryTest,IndexedSyncedRepositoryTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/MemberRepository.java src/test/java/com/astralrealms/skyblock/repository/MemberRepositoryTest.java
git commit -m "refactor: migrate MemberRepository to IndexedSyncedRepository"
```

---

### Task 4: Migrate `RoleRepository` onto the base

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/repository/RoleRepository.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/RoleRepositoryTest.java`

**Interfaces:**
- Consumes: `IndexedSyncedRepository<Long, IslandRole, UUID>`.
- Produces: `getIslandRoleIds(UUID)` → delegates to `keysIn`; `findByIsland(UUID)` → orders results then primes.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/RoleRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RoleRepositoryTest {

    AstralSkyblock plugin;
    DatabaseService database;
    RoleRepository repo;
    UUID island;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        database = plugin.database();
        repo = new RoleRepository(plugin);
        island = UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesRoleIdsForIsland() {
        IslandRole role = new IslandRole(7L, island, IslandRole.Type.MEMBER, "Member", 10, true, 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(role)));

        repo.prime(island).join();

        assertThat(repo.getIslandRoleIds(island)).containsExactly(7L);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=RoleRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Rewrite `RoleRepository` to extend the base**

Change the class header (line 39) from:

```java
public class RoleRepository extends SyncedRepository<Long, IslandRole> {
```

to:

```java
public class RoleRepository extends IndexedSyncedRepository<Long, IslandRole, UUID> {
```

Delete the `islandRoleIndex` field (line 44), the `buildCache` override (lines 61-70), and the `cacheLocally` override (lines 72-76). Replace `getIslandRoleIds` (lines 78-81) with:

```java
    @Unmodifiable
    public Collection<Long> getIslandRoleIds(UUID islandId) {
        return keysIn(islandId);
    }
```

Replace `findByIsland` (lines 90-111) with:

```java
    /** All of an island's roles, senior first (weight DESC, id). Primes the per-role cache. */
    public CompletableFuture<List<IslandRole>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    @Override
    protected I indexKeyOf(IslandRole value) {
        return value.islandId();
    }
```

(Wait — `indexKeyOf` returns `UUID`, not `I`. Use `UUID`.) Add the two base hooks after the existing `keyFromValue`:

```java
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
```

In `insert` (lines 321-324) the `thenApply` currently does `this.islandRoleIndex.put(...)`. Replace that with `index(saved)`:

```java
                .thenApply(saved -> {
                    index(saved);
                    return saved;
                });
```

In `setDefault` (line 185) and `delete` (line 211) replace `this.islandRoleIndex.get(islandId)` / `this.islandRoleIndex.remove(...)` with the base equivalents:
- line 185: `List.copyOf(keysIn(islandId)).forEach(this::invalidateGlobally);`
- line 211: `invalidateLocally(doomedRoleId);` (the eviction/deindex now handles the slice; keep the subsequent `invalidateGlobally(doomedRoleId)`).

In `deleteFromDatabase` (lines 267-276) the `thenRun(() -> this.islandRoleIndex.values().remove(key))` is now redundant (the global invalidation path de-indexes). Replace its body with `.thenRun(() -> { })` or drop the `thenRun` entirely so it returns the run future directly. Remove the now-unused `HashMultimap`/`Multimap`/`Multimaps` imports; keep `ArrayList`, `List`, `Collection`, `UUID`, `Unmodifiable`.

- [ ] **Step 4: Run the tests (expect PASS)**

Run: `mvn -q test -Dtest=RoleRepositoryTest,MemberRepositoryTest,IndexedSyncedRepositoryTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/RoleRepository.java src/test/java/com/astralrealms/skyblock/repository/RoleRepositoryTest.java
git commit -m "refactor: migrate RoleRepository to IndexedSyncedRepository"
```

---

### Task 5: `PermissionRepository` island prime/evict helpers

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/repository/PermissionRepository.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/PermissionRepositoryTest.java`

**Interfaces:**
- Produces: `prime(UUID islandId)` → `CompletableFuture<Void>` (reuses existing `findByIsland`); `evict(Collection<Long> roleIds)` → L1-only invalidation of the island's role grant sets.

`PermissionRepository` stays on `SyncedRepository` (keyed by role id; permission lookups are always by role id the caller already holds). It only needs island-scoped lifecycle helpers.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/PermissionRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.role.RolePermissions;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRepositoryTest {

    @Test
    void evictDropsRoleGrantSetsFromL1() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        PermissionRepository repo = new PermissionRepository(plugin);
        repo.cache().synchronous().put(5L, new RolePermissions(5L, Set.of("island.invite")));

        repo.evict(List.of(5L));

        assertThat(repo.findCachedById(5L)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=PermissionRepositoryTest`
Expected: FAIL / compile error — `evict` does not exist.

- [ ] **Step 3: Add the helpers**

In `PermissionRepository.java`, after the existing `findByIsland` method (line 78), add:

```java
    /** Primes every role grant set of an island into L1 (lifecycle activation). */
    public CompletableFuture<Void> prime(UUID islandId) {
        return findByIsland(islandId).thenApply(ignored -> null);
    }

    /** Drops the given roles' grant sets from this server's L1 cache (lifecycle deactivation). */
    public void evict(Collection<Long> roleIds) {
        roleIds.forEach(this::invalidateLocally);
    }
```

Add imports `java.util.Collection` and (if missing) `java.util.concurrent.CompletableFuture` is already present.

- [ ] **Step 4: Run the tests (expect PASS)**

Run: `mvn -q test -Dtest=PermissionRepositoryTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/repository/PermissionRepository.java src/test/java/com/astralrealms/skyblock/repository/PermissionRepositoryTest.java
git commit -m "feat: add island prime/evict helpers to PermissionRepository"
```

---

### Task 6: Composite keys, generic packets, constants

**Files:**
- Create: `model/island/WarpKey.java`, `model/island/UpgradeKey.java`, `model/island/FlagKey.java`, `model/member/IslandPlayerKey.java`
- Create: `messaging/packet/repository/IslandStringKeyUpdatePacket.java`, `IslandStringKeyDeletePacket.java`, `IslandPlayerKeyUpdatePacket.java`, `IslandPlayerKeyDeletePacket.java`
- Modify: `messaging/ASPacketRegistry.java`, `utils/ASConstants.java`
- Test: `src/test/java/com/astralrealms/skyblock/messaging/PacketRoundTripTest.java`

**Interfaces:**
- Produces: `WarpKey(UUID islandId, String name)`, `UpgradeKey(UUID islandId, String upgrade)`, `FlagKey(UUID islandId, String flag)`, `IslandPlayerKey(UUID islandId, UUID playerUuid)`; four packets; constants `WARP_CACHE_KEY`/`WARP_UPDATE_CHANNEL` and the same for `UPGRADE`/`FLAG`/`BAN`/`COOP`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/messaging/PacketRoundTripTest.java`:

```java
package com.astralrealms.skyblock.messaging;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.packet.binary.BinaryMessage;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;

import static org.assertj.core.api.Assertions.assertThat;

class PacketRoundTripTest {

    @Test
    void islandStringKeyPacketRoundTrips() {
        UUID island = UUID.randomUUID();
        IslandStringKeyUpdatePacket original = new IslandStringKeyUpdatePacket(island, "home");

        BinaryMessage buffer = new BinaryMessage();
        original.write(buffer);

        IslandStringKeyUpdatePacket decoded = new IslandStringKeyUpdatePacket();
        decoded.read(buffer);

        assertThat(decoded.islandId()).isEqualTo(island);
        assertThat(decoded.key()).isEqualTo("home");
    }
}
```

> If `BinaryMessage` has no no-arg constructor or needs a backing buffer to be re-read, adapt this test to the core API (e.g. construct a read buffer from the written bytes). Inspect `MemberObjectUpdatePacket`'s usage / the `BinaryMessage` class to confirm the read/write idiom before finalizing.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=PacketRoundTripTest`
Expected: FAIL / compile error — packet class missing.

- [ ] **Step 3: Create the key records**

`model/island/WarpKey.java`:

```java
package com.astralrealms.skyblock.model.island;

import java.util.UUID;

public record WarpKey(UUID islandId, String name) {
}
```

`model/island/UpgradeKey.java`:

```java
package com.astralrealms.skyblock.model.island;

import java.util.UUID;

public record UpgradeKey(UUID islandId, String upgrade) {
}
```

`model/island/FlagKey.java`:

```java
package com.astralrealms.skyblock.model.island;

import java.util.UUID;

public record FlagKey(UUID islandId, String flag) {
}
```

`model/member/IslandPlayerKey.java`:

```java
package com.astralrealms.skyblock.model.member;

import java.util.UUID;

public record IslandPlayerKey(UUID islandId, UUID playerUuid) {
}
```

- [ ] **Step 4: Create the packets**

`messaging/packet/repository/IslandStringKeyUpdatePacket.java`:

```java
package com.astralrealms.skyblock.messaging.packet.repository;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandStringKeyUpdatePacket implements Packet {

    private UUID islandId;
    private String key;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeString(key);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.key = binaryMessage.readString();
    }
}
```

`messaging/packet/repository/IslandStringKeyDeletePacket.java` — identical body, class name `IslandStringKeyDeletePacket`.

`messaging/packet/repository/IslandPlayerKeyUpdatePacket.java`:

```java
package com.astralrealms.skyblock.messaging.packet.repository;

import java.util.UUID;

import com.astralrealms.core.packet.Packet;
import com.astralrealms.core.packet.binary.BinaryMessage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IslandPlayerKeyUpdatePacket implements Packet {

    private UUID islandId;
    private UUID playerUuid;

    @Override
    public void write(BinaryMessage binaryMessage) {
        binaryMessage.writeUUID(islandId);
        binaryMessage.writeUUID(playerUuid);
    }

    @Override
    public void read(BinaryMessage binaryMessage) {
        this.islandId = binaryMessage.readUUID();
        this.playerUuid = binaryMessage.readUUID();
    }
}
```

`messaging/packet/repository/IslandPlayerKeyDeletePacket.java` — identical body, class name `IslandPlayerKeyDeletePacket`.

> Confirm `writeString`/`readString` exist on `BinaryMessage` (the existing packets use `writeUUID`/`readUUID`). If the method is named differently (e.g. `writeUtf`), use that name consistently in all four String-key spots.

- [ ] **Step 5: Register the packets**

In `messaging/ASPacketRegistry.java`, add imports for the four new packets and register them after `0x05` (before the islands block):

```java
        this.registerPacket(0x06, IslandStringKeyUpdatePacket::new);
        this.registerPacket(0x07, IslandStringKeyDeletePacket::new);
        this.registerPacket(0x08, IslandPlayerKeyUpdatePacket::new);
        this.registerPacket(0x09, IslandPlayerKeyDeletePacket::new);
```

- [ ] **Step 6: Add constants**

In `utils/ASConstants.java`, add to the cache-keys block:

```java
    public static final String WARP_CACHE_KEY = "skyblock:warps";
    public static final String UPGRADE_CACHE_KEY = "skyblock:upgrades";
    public static final String FLAG_CACHE_KEY = "skyblock:flags";
    public static final String BAN_CACHE_KEY = "skyblock:bans";
    public static final String COOP_CACHE_KEY = "skyblock:coops";
```

and to the channels block:

```java
    public static final String WARP_UPDATE_CHANNEL = "skyblock.warp.update";
    public static final String UPGRADE_UPDATE_CHANNEL = "skyblock.upgrade.update";
    public static final String FLAG_UPDATE_CHANNEL = "skyblock.flag.update";
    public static final String BAN_UPDATE_CHANNEL = "skyblock.ban.update";
    public static final String COOP_UPDATE_CHANNEL = "skyblock.coop.update";
```

- [ ] **Step 7: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=PacketRoundTripTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/model/island/WarpKey.java src/main/java/com/astralrealms/skyblock/model/island/UpgradeKey.java src/main/java/com/astralrealms/skyblock/model/island/FlagKey.java src/main/java/com/astralrealms/skyblock/model/member/IslandPlayerKey.java src/main/java/com/astralrealms/skyblock/messaging/packet/repository/IslandStringKey*.java src/main/java/com/astralrealms/skyblock/messaging/packet/repository/IslandPlayerKey*.java src/main/java/com/astralrealms/skyblock/messaging/ASPacketRegistry.java src/main/java/com/astralrealms/skyblock/utils/ASConstants.java src/test/java/com/astralrealms/skyblock/messaging/PacketRoundTripTest.java
git commit -m "feat: add composite keys, generic island packets, and cache constants"
```

---

### Task 7: `WarpRepository` + `WarpService`

**Files:**
- Create: `repository/WarpRepository.java`, `service/WarpService.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/WarpRepositoryTest.java`

**Interfaces:**
- Consumes: `IndexedSyncedRepository<WarpKey, IslandWarp, UUID>`, packets `IslandStringKey*`, constants `WARP_*`.
- Produces: `WarpRepository.findByIsland(UUID)`/`prime`/`keysIn`/`set(IslandWarp)`/`remove(UUID,String)`; `WarpService.warps(UUID)` → `Collection<IslandWarp>` (cached, local-only), `WarpService.set/remove`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/WarpRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WarpRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesWarpsByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        WarpRepository repo = new WarpRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandWarp warp = new IslandWarp(island, "home", 1, 2, 3, 0f, 0f, false, 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(warp)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new WarpKey(island, "home"));
        assertThat(repo.findCachedById(new WarpKey(island, "home"))).isPresent();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=WarpRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement `WarpRepository`**

Create `src/main/java/com/astralrealms/skyblock/repository/WarpRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island warps, keyed by the composite {@link WarpKey} (island + name). */
public class WarpRepository extends IndexedSyncedRepository<WarpKey, IslandWarp, UUID> {

    private static final String COLUMNS = "island_id, name, x, y, z, yaw, pitch, is_private, created_at";

    public WarpRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.WARP_CACHE_KEY, ASConstants.WARP_UPDATE_CHANNEL, IslandWarp.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new WarpKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new WarpKey(delete.islandId(), delete.key()));
        });
    }

    /** All warps of an island. Primes the per-warp cache and index. */
    public CompletableFuture<List<IslandWarp>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    /** Creates or updates a warp. */
    public CompletableFuture<IslandWarp> set(IslandWarp warp) {
        return save(warp);
    }

    /** Removes a warp. */
    public CompletableFuture<IslandWarp> remove(UUID islandId, String name) {
        return delete(new WarpKey(islandId, name));
    }

    @Override
    protected UUID indexKeyOf(IslandWarp value) {
        return value.islandId();
    }

    @Override
    protected WarpKey keyFromValue(IslandWarp value) {
        return new WarpKey(value.islandId(), value.name());
    }

    @Override
    protected String cacheKey(WarpKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.name();
    }

    @Override
    protected CompletableFuture<IslandWarp> loadById(WarpKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ? AND name = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandWarp>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_warps WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandWarp> warps = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        warps.add(map(resultSet));
                }
            }
            return warps;
        });
    }

    @Override
    protected CompletableFuture<IslandWarp> saveToDatabase(IslandWarp value) {
        String query = "INSERT INTO island_warps (island_id, name, x, y, z, yaw, pitch, is_private) "
                       + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE x = VALUES(x), y = VALUES(y), z = VALUES(z), "
                       + "yaw = VALUES(yaw), pitch = VALUES(pitch), is_private = VALUES(is_private)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.name());
                statement.setDouble(3, value.x());
                statement.setDouble(4, value.y());
                statement.setDouble(5, value.z());
                statement.setFloat(6, value.yaw());
                statement.setFloat(7, value.pitch());
                statement.setBoolean(8, value.isPrivate());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(WarpKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_warps WHERE island_id = ? AND name = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.name());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(WarpKey key, IslandWarp value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.name()));
    }

    @Override
    protected void publishInvalidation(WarpKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.name()));
    }

    private IslandWarp map(ResultSet resultSet) throws SQLException {
        return new IslandWarp(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                resultSet.getFloat("yaw"),
                resultSet.getFloat("pitch"),
                resultSet.getBoolean("is_private"),
                resultSet.getTimestamp("created_at") == null ? 0L : resultSet.getTimestamp("created_at").getTime()
        );
    }
}
```

- [ ] **Step 4: Implement `WarpService`**

Create `src/main/java/com/astralrealms/skyblock/service/WarpService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.repository.WarpRepository;

import lombok.Getter;

@Getter
public class WarpService {

    private final AstralSkyblock plugin;
    private final WarpRepository repository;

    public WarpService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new WarpRepository(plugin);
    }

    /** The island's warps from the local cache (empty if the island is not active here). */
    @Unmodifiable
    public Collection<IslandWarp> warps(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandWarp> set(IslandWarp warp) {
        return this.repository.set(warp);
    }

    public CompletableFuture<IslandWarp> remove(UUID islandId, String name) {
        return this.repository.remove(islandId, name);
    }
}
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=WarpRepositoryTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/repository/WarpRepository.java src/main/java/com/astralrealms/skyblock/service/WarpService.java src/test/java/com/astralrealms/skyblock/repository/WarpRepositoryTest.java
git commit -m "feat: add WarpRepository and WarpService"
```

---

### Task 8: `UpgradeRepository` + `UpgradeService`

**Files:**
- Create: `repository/UpgradeRepository.java`, `service/UpgradeService.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/UpgradeRepositoryTest.java`

**Interfaces:**
- Produces: `UpgradeRepository.findByIsland/prime/keysIn/setLevel(UUID,String,int)/remove(UUID,String)`; `UpgradeService.upgrades(UUID)`, `setLevel`, `remove`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/UpgradeRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.model.island.UpgradeKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class UpgradeRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesUpgradesByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        UpgradeRepository repo = new UpgradeRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandUpgrade upgrade = new IslandUpgrade(island, "crop_growth", 3);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(upgrade)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new UpgradeKey(island, "crop_growth"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=UpgradeRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement `UpgradeRepository`**

Create `src/main/java/com/astralrealms/skyblock/repository/UpgradeRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.model.island.UpgradeKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island upgrade levels, keyed by the composite {@link UpgradeKey} (island + upgrade name). */
public class UpgradeRepository extends IndexedSyncedRepository<UpgradeKey, IslandUpgrade, UUID> {

    private static final String COLUMNS = "island_id, upgrade, level";

    public UpgradeRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.UPGRADE_CACHE_KEY, ASConstants.UPGRADE_UPDATE_CHANNEL, IslandUpgrade.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new UpgradeKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new UpgradeKey(delete.islandId(), delete.key()));
        });
    }

    public CompletableFuture<List<IslandUpgrade>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandUpgrade> setLevel(UUID islandId, String upgrade, int level) {
        return save(new IslandUpgrade(islandId, upgrade, level));
    }

    public CompletableFuture<IslandUpgrade> remove(UUID islandId, String upgrade) {
        return delete(new UpgradeKey(islandId, upgrade));
    }

    @Override
    protected UUID indexKeyOf(IslandUpgrade value) {
        return value.islandId();
    }

    @Override
    protected UpgradeKey keyFromValue(IslandUpgrade value) {
        return new UpgradeKey(value.islandId(), value.upgrade());
    }

    @Override
    protected String cacheKey(UpgradeKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.upgrade();
    }

    @Override
    protected CompletableFuture<IslandUpgrade> loadById(UpgradeKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_upgrades WHERE island_id = ? AND upgrade = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.upgrade());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandUpgrade>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_upgrades WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandUpgrade> upgrades = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        upgrades.add(map(resultSet));
                }
            }
            return upgrades;
        });
    }

    @Override
    protected CompletableFuture<IslandUpgrade> saveToDatabase(IslandUpgrade value) {
        String query = "INSERT INTO island_upgrades (island_id, upgrade, level) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE level = VALUES(level)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.upgrade());
                statement.setInt(3, value.level());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(UpgradeKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_upgrades WHERE island_id = ? AND upgrade = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.upgrade());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(UpgradeKey key, IslandUpgrade value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.upgrade()));
    }

    @Override
    protected void publishInvalidation(UpgradeKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.upgrade()));
    }

    private IslandUpgrade map(ResultSet resultSet) throws SQLException {
        return new IslandUpgrade(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("upgrade"),
                resultSet.getInt("level")
        );
    }
}
```

- [ ] **Step 4: Implement `UpgradeService`**

Create `src/main/java/com/astralrealms/skyblock/service/UpgradeService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.repository.UpgradeRepository;

import lombok.Getter;

@Getter
public class UpgradeService {

    private final AstralSkyblock plugin;
    private final UpgradeRepository repository;

    public UpgradeService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new UpgradeRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandUpgrade> upgrades(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandUpgrade> setLevel(UUID islandId, String upgrade, int level) {
        return this.repository.setLevel(islandId, upgrade, level);
    }

    public CompletableFuture<IslandUpgrade> remove(UUID islandId, String upgrade) {
        return this.repository.remove(islandId, upgrade);
    }
}
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=UpgradeRepositoryTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/repository/UpgradeRepository.java src/main/java/com/astralrealms/skyblock/service/UpgradeService.java src/test/java/com/astralrealms/skyblock/repository/UpgradeRepositoryTest.java
git commit -m "feat: add UpgradeRepository and UpgradeService"
```

---

### Task 9: `FlagRepository` + `FlagService`

**Files:**
- Create: `repository/FlagRepository.java`, `service/FlagService.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/FlagRepositoryTest.java`

**Interfaces:**
- Produces: `FlagRepository.findByIsland/prime/keysIn/set(UUID,String,boolean)/remove(UUID,String)`; `FlagService.flags(UUID)`, `set`, `remove`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/FlagRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.FlagKey;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FlagRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesFlagsByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        FlagRepository repo = new FlagRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandFlag flag = new IslandFlag(island, "pvp", false);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(flag)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new FlagKey(island, "pvp"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=FlagRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement `FlagRepository`**

Create `src/main/java/com/astralrealms/skyblock/repository/FlagRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandStringKeyUpdatePacket;
import com.astralrealms.skyblock.model.island.FlagKey;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island protection flags, keyed by the composite {@link FlagKey} (island + flag name). */
public class FlagRepository extends IndexedSyncedRepository<FlagKey, IslandFlag, UUID> {

    private static final String COLUMNS = "island_id, flag, allowed";

    public FlagRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.FLAG_CACHE_KEY, ASConstants.FLAG_UPDATE_CHANNEL, IslandFlag.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandStringKeyUpdatePacket update)
                cache.synchronous().refresh(new FlagKey(update.islandId(), update.key()));
            else if (packet instanceof IslandStringKeyDeletePacket delete)
                invalidateLocally(new FlagKey(delete.islandId(), delete.key()));
        });
    }

    public CompletableFuture<List<IslandFlag>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandFlag> set(UUID islandId, String flag, boolean allowed) {
        return save(new IslandFlag(islandId, flag, allowed));
    }

    public CompletableFuture<IslandFlag> remove(UUID islandId, String flag) {
        return delete(new FlagKey(islandId, flag));
    }

    @Override
    protected UUID indexKeyOf(IslandFlag value) {
        return value.islandId();
    }

    @Override
    protected FlagKey keyFromValue(IslandFlag value) {
        return new FlagKey(value.islandId(), value.flag());
    }

    @Override
    protected String cacheKey(FlagKey key) {
        return this.cacheKey + ":" + key.islandId() + ":" + key.flag();
    }

    @Override
    protected CompletableFuture<IslandFlag> loadById(FlagKey key) {
        String query = "SELECT " + COLUMNS + " FROM island_flags WHERE island_id = ? AND flag = ?";
        return this.plugin.database().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.flag());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? map(resultSet) : null;
                }
            }
        });
    }

    @Override
    protected CompletableFuture<List<IslandFlag>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_flags WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
            List<IslandFlag> flags = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, islandId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next())
                        flags.add(map(resultSet));
                }
            }
            return flags;
        });
    }

    @Override
    protected CompletableFuture<IslandFlag> saveToDatabase(IslandFlag value) {
        String query = "INSERT INTO island_flags (island_id, flag, allowed) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE allowed = VALUES(allowed)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setString(2, value.flag());
                statement.setBoolean(3, value.allowed());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(FlagKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_flags WHERE island_id = ? AND flag = ?")) {
                statement.setObject(1, key.islandId());
                statement.setString(2, key.flag());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(FlagKey key, IslandFlag value) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyUpdatePacket(key.islandId(), key.flag()));
    }

    @Override
    protected void publishInvalidation(FlagKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandStringKeyDeletePacket(key.islandId(), key.flag()));
    }

    private IslandFlag map(ResultSet resultSet) throws SQLException {
        return new IslandFlag(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getString("flag"),
                resultSet.getBoolean("allowed")
        );
    }
}
```

- [ ] **Step 4: Implement `FlagService`**

Create `src/main/java/com/astralrealms/skyblock/service/FlagService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.repository.FlagRepository;

import lombok.Getter;

@Getter
public class FlagService {

    private final AstralSkyblock plugin;
    private final FlagRepository repository;

    public FlagService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new FlagRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandFlag> flags(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public CompletableFuture<IslandFlag> set(UUID islandId, String flag, boolean allowed) {
        return this.repository.set(islandId, flag, allowed);
    }

    public CompletableFuture<IslandFlag> remove(UUID islandId, String flag) {
        return this.repository.remove(islandId, flag);
    }
}
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=FlagRepositoryTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/repository/FlagRepository.java src/main/java/com/astralrealms/skyblock/service/FlagService.java src/test/java/com/astralrealms/skyblock/repository/FlagRepositoryTest.java
git commit -m "feat: add FlagRepository and FlagService"
```

---

### Task 10: `BanRepository` + `BanService`

**Files:**
- Create: `repository/BanRepository.java`, `service/BanService.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/BanRepositoryTest.java`

**Interfaces:**
- Consumes: `IslandPlayerKey`, packets `IslandPlayerKey*`, constants `BAN_*`.
- Produces: `BanRepository.findByIsland/prime/keysIn/ban(IslandBan)/unban(UUID,UUID)`; `BanService.bans(UUID)`, `ban`, `unban`, `isBanned(UUID,UUID)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/BanRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class BanRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesBansByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        BanRepository repo = new BanRepository(plugin);
        UUID island = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        IslandBan ban = new IslandBan(island, player, UUID.randomUUID(), "griefing", 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(ban)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new IslandPlayerKey(island, player));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=BanRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement `BanRepository`**

Create `src/main/java/com/astralrealms/skyblock/repository/BanRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island bans, keyed by the composite {@link IslandPlayerKey} (island + player). */
public class BanRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandBan, UUID> {

    private static final String COLUMNS = "island_id, player_uuid, banned_by, reason, created_at";

    public BanRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.BAN_CACHE_KEY, ASConstants.BAN_UPDATE_CHANNEL, IslandBan.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandPlayerKeyUpdatePacket update)
                cache.synchronous().refresh(new IslandPlayerKey(update.islandId(), update.playerUuid()));
            else if (packet instanceof IslandPlayerKeyDeletePacket delete)
                invalidateLocally(new IslandPlayerKey(delete.islandId(), delete.playerUuid()));
        });
    }

    public CompletableFuture<List<IslandBan>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandBan> ban(IslandBan ban) {
        return save(ban);
    }

    public CompletableFuture<IslandBan> unban(UUID islandId, UUID playerUuid) {
        return delete(new IslandPlayerKey(islandId, playerUuid));
    }

    @Override
    protected UUID indexKeyOf(IslandBan value) {
        return value.islandId();
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
        String query = "SELECT " + COLUMNS + " FROM island_bans WHERE island_id = ? AND player_uuid = ?";
        return this.plugin.database().supply(connection -> {
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
    protected CompletableFuture<List<IslandBan>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_bans WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
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
    protected CompletableFuture<IslandBan> saveToDatabase(IslandBan value) {
        String query = "INSERT INTO island_bans (island_id, player_uuid, banned_by, reason) VALUES (?, ?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE banned_by = VALUES(banned_by), reason = VALUES(reason)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setObject(2, value.playerUuid());
                statement.setObject(3, value.bannedBy());
                statement.setString(4, value.reason());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_bans WHERE island_id = ? AND player_uuid = ?")) {
                statement.setObject(1, key.islandId());
                statement.setObject(2, key.playerUuid());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandBan value) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyDeletePacket(key.islandId(), key.playerUuid()));
    }

    private IslandBan map(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new IslandBan(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                resultSet.getObject("banned_by", UUID.class),
                resultSet.getString("reason"),
                createdAt == null ? 0L : createdAt.getTime()
        );
    }
}
```

- [ ] **Step 4: Implement `BanService`**

Create `src/main/java/com/astralrealms/skyblock/service/BanService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.repository.BanRepository;

import lombok.Getter;

@Getter
public class BanService {

    private final AstralSkyblock plugin;
    private final BanRepository repository;

    public BanService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new BanRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandBan> bans(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Whether the player is banned from the island, per the local cache. */
    public boolean isBanned(UUID islandId, UUID playerUuid) {
        return this.repository.findCachedById(new IslandPlayerKey(islandId, playerUuid)).isPresent();
    }

    public CompletableFuture<IslandBan> ban(IslandBan ban) {
        return this.repository.ban(ban);
    }

    public CompletableFuture<IslandBan> unban(UUID islandId, UUID playerUuid) {
        return this.repository.unban(islandId, playerUuid);
    }
}
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=BanRepositoryTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/repository/BanRepository.java src/main/java/com/astralrealms/skyblock/service/BanService.java src/test/java/com/astralrealms/skyblock/repository/BanRepositoryTest.java
git commit -m "feat: add BanRepository and BanService"
```

---

### Task 11: `CoopRepository` + `CoopService`

**Files:**
- Create: `repository/CoopRepository.java`, `service/CoopService.java`
- Test: `src/test/java/com/astralrealms/skyblock/repository/CoopRepositoryTest.java`

**Interfaces:**
- Produces: `CoopRepository.findByIsland/prime/keysIn/add(IslandCoop)/remove(UUID,UUID)`; `CoopService.coops(UUID)`, `add`, `remove`, `isCoop(UUID,UUID)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/repository/CoopRepositoryTest.java`:

```java
package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CoopRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesCoopsByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        CoopRepository repo = new CoopRepository(plugin);
        UUID island = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        IslandCoop coop = new IslandCoop(island, player, UUID.randomUUID(), 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(coop)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new IslandPlayerKey(island, player));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=CoopRepositoryTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Implement `CoopRepository`**

Create `src/main/java/com/astralrealms/skyblock/repository/CoopRepository.java`:

```java
package com.astralrealms.skyblock.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyDeletePacket;
import com.astralrealms.skyblock.messaging.packet.repository.IslandPlayerKeyUpdatePacket;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.utils.ASConstants;

/** Per-island coop grants, keyed by the composite {@link IslandPlayerKey} (island + player). */
public class CoopRepository extends IndexedSyncedRepository<IslandPlayerKey, IslandCoop, UUID> {

    private static final String COLUMNS = "island_id, player_uuid, added_by, created_at";

    public CoopRepository(AstralSkyblock plugin) {
        super(plugin, ASConstants.COOP_CACHE_KEY, ASConstants.COOP_UPDATE_CHANNEL, IslandCoop.class);
        this.plugin.messaging().registerExchange(exchangeChannel, packet -> {
            if (packet instanceof IslandPlayerKeyUpdatePacket update)
                cache.synchronous().refresh(new IslandPlayerKey(update.islandId(), update.playerUuid()));
            else if (packet instanceof IslandPlayerKeyDeletePacket delete)
                invalidateLocally(new IslandPlayerKey(delete.islandId(), delete.playerUuid()));
        });
    }

    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return prime(islandId);
    }

    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return save(coop);
    }

    public CompletableFuture<IslandCoop> remove(UUID islandId, UUID playerUuid) {
        return delete(new IslandPlayerKey(islandId, playerUuid));
    }

    @Override
    protected UUID indexKeyOf(IslandCoop value) {
        return value.islandId();
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
        String query = "SELECT " + COLUMNS + " FROM island_coops WHERE island_id = ? AND player_uuid = ?";
        return this.plugin.database().supply(connection -> {
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
    protected CompletableFuture<List<IslandCoop>> loadByIndex(UUID islandId) {
        String query = "SELECT " + COLUMNS + " FROM island_coops WHERE island_id = ?";
        return this.plugin.database().supply(connection -> {
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
    protected CompletableFuture<IslandCoop> saveToDatabase(IslandCoop value) {
        String query = "INSERT INTO island_coops (island_id, player_uuid, added_by) VALUES (?, ?, ?) "
                       + "ON DUPLICATE KEY UPDATE added_by = VALUES(added_by)";
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setObject(1, value.islandId());
                statement.setObject(2, value.playerUuid());
                statement.setObject(3, value.addedBy());
                statement.executeUpdate();
            }
        }).thenApply(ignored -> value);
    }

    @Override
    protected CompletableFuture<Void> deleteFromDatabase(IslandPlayerKey key) {
        return this.plugin.database().run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_coops WHERE island_id = ? AND player_uuid = ?")) {
                statement.setObject(1, key.islandId());
                statement.setObject(2, key.playerUuid());
                statement.executeUpdate();
            }
        });
    }

    @Override
    protected void publishUpdate(IslandPlayerKey key, IslandCoop value) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyUpdatePacket(key.islandId(), key.playerUuid()));
    }

    @Override
    protected void publishInvalidation(IslandPlayerKey key) {
        this.plugin.messaging().send(exchangeChannel, new IslandPlayerKeyDeletePacket(key.islandId(), key.playerUuid()));
    }

    private IslandCoop map(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new IslandCoop(
                resultSet.getObject("island_id", UUID.class),
                resultSet.getObject("player_uuid", UUID.class),
                resultSet.getObject("added_by", UUID.class),
                createdAt == null ? 0L : createdAt.getTime()
        );
    }
}
```

- [ ] **Step 4: Implement `CoopService`**

Create `src/main/java/com/astralrealms/skyblock/service/CoopService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.repository.CoopRepository;

import lombok.Getter;

@Getter
public class CoopService {

    private final AstralSkyblock plugin;
    private final CoopRepository repository;

    public CoopService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CoopRepository(plugin);
    }

    @Unmodifiable
    public Collection<IslandCoop> coops(UUID islandId) {
        return this.repository.keysIn(islandId).stream()
                .map(key -> this.repository.findCachedById(key).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return this.repository.findCachedById(new IslandPlayerKey(islandId, playerUuid)).isPresent();
    }

    public CompletableFuture<IslandCoop> add(IslandCoop coop) {
        return this.repository.add(coop);
    }

    public CompletableFuture<IslandCoop> remove(UUID islandId, UUID playerUuid) {
        return this.repository.remove(islandId, playerUuid);
    }
}
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=CoopRepositoryTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/repository/CoopRepository.java src/main/java/com/astralrealms/skyblock/service/CoopService.java src/test/java/com/astralrealms/skyblock/repository/CoopRepositoryTest.java
git commit -m "feat: add CoopRepository and CoopService"
```

---

### Task 12: Wire new services into `AstralSkyblock`

This task is brought forward (before the context service) so the `IslandContextService` can reference real getters. No new behavior beyond construction + getters.

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/AstralSkyblock.java`

**Interfaces:**
- Produces: `plugin.warps()`, `plugin.upgrades()`, `plugin.flags()`, `plugin.bans()`, `plugin.coops()` (and later `plugin.islandContext()`), via Lombok `@Getter` fields.

- [ ] **Step 1: Add the fields**

In the `// Services` field block, add after `private ServerService servers;`:

```java
    private WarpService warps;
    private UpgradeService upgrades;
    private FlagService flags;
    private BanService bans;
    private CoopService coops;
```

- [ ] **Step 2: Construct them**

In `onEnable()`, after `this.servers = new ServerService(this);`, add:

```java
        this.warps = new WarpService(this);
        this.upgrades = new UpgradeService(this);
        this.flags = new FlagService(this);
        this.bans = new BanService(this);
        this.coops = new CoopService(this);
```

Add the corresponding imports for the five service classes.

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/AstralSkyblock.java
git commit -m "feat: wire warp/upgrade/flag/ban/coop services into plugin"
```

---

### Task 13: `IslandContextService`

**Files:**
- Create: `src/main/java/com/astralrealms/skyblock/service/IslandContextService.java`
- Modify: `src/main/java/com/astralrealms/skyblock/AstralSkyblock.java` (field + construction + getter)
- Test: `src/test/java/com/astralrealms/skyblock/service/IslandContextServiceTest.java`

**Interfaces:**
- Consumes: all repositories' `prime`/`evictIndex` (members/roles/warps/upgrades/flags/bans/coops) + `PermissionRepository.prime`/`evict` + `RoleRepository.getIslandRoleIds`.
- Produces: `activate(UUID)` → `CompletableFuture<Void>` (idempotent); `deactivate(UUID)` → `void`; `isActive(UUID)` → `boolean`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/service/IslandContextServiceTest.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.repository.BanRepository;
import com.astralrealms.skyblock.repository.CoopRepository;
import com.astralrealms.skyblock.repository.FlagRepository;
import com.astralrealms.skyblock.repository.MemberRepository;
import com.astralrealms.skyblock.repository.PermissionRepository;
import com.astralrealms.skyblock.repository.RoleRepository;
import com.astralrealms.skyblock.repository.UpgradeRepository;
import com.astralrealms.skyblock.repository.WarpRepository;
import com.astralrealms.skyblock.service.BanService;
import com.astralrealms.skyblock.service.CoopService;
import com.astralrealms.skyblock.service.FlagService;
import com.astralrealms.skyblock.service.MemberService;
import com.astralrealms.skyblock.service.RoleService;
import com.astralrealms.skyblock.service.UpgradeService;
import com.astralrealms.skyblock.service.WarpService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IslandContextServiceTest {

    AstralSkyblock plugin;
    MemberRepository members;
    RoleRepository roles;
    PermissionRepository permissions;
    WarpRepository warps;
    UpgradeRepository upgrades;
    FlagRepository flags;
    BanRepository bans;
    CoopRepository coops;
    IslandContextService context;
    UUID island;

    @BeforeEach
    void setUp() {
        plugin = mock(AstralSkyblock.class);
        members = mock(MemberRepository.class);
        roles = mock(RoleRepository.class);
        permissions = mock(PermissionRepository.class);
        warps = mock(WarpRepository.class);
        upgrades = mock(UpgradeRepository.class);
        flags = mock(FlagRepository.class);
        bans = mock(BanRepository.class);
        coops = mock(CoopRepository.class);

        wire(MemberService.class, members, plugin::members);
        // Each *Service exposes repository(); stub the plugin getters to return services wrapping the mock repos.
        MemberService memberService = mock(MemberService.class);
        RoleService roleService = mock(RoleService.class);
        WarpService warpService = mock(WarpService.class);
        UpgradeService upgradeService = mock(UpgradeService.class);
        FlagService flagService = mock(FlagService.class);
        BanService banService = mock(BanService.class);
        CoopService coopService = mock(CoopService.class);

        lenient().when(memberService.repository()).thenReturn(members);
        lenient().when(roleService.repository()).thenReturn(roles);
        lenient().when(warpService.repository()).thenReturn(warps);
        lenient().when(upgradeService.repository()).thenReturn(upgrades);
        lenient().when(flagService.repository()).thenReturn(flags);
        lenient().when(banService.repository()).thenReturn(bans);
        lenient().when(coopService.repository()).thenReturn(coops);

        lenient().when(plugin.members()).thenReturn(memberService);
        lenient().when(plugin.roles()).thenReturn(roleService);
        lenient().when(plugin.warps()).thenReturn(warpService);
        lenient().when(plugin.upgrades()).thenReturn(upgradeService);
        lenient().when(plugin.flags()).thenReturn(flagService);
        lenient().when(plugin.bans()).thenReturn(banService);
        lenient().when(plugin.coops()).thenReturn(coopService);
        lenient().when(plugin.permissions()).thenReturn(permissions);

        lenient().when(members.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(roles.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(warps.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(upgrades.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(flags.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(bans.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(coops.prime(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(permissions.prime(any())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(roles.getIslandRoleIds(any())).thenReturn(List.of());

        context = new IslandContextService(plugin);
        island = UUID.randomUUID();
    }

    private <T> void wire(Class<T> type, Object repo, Object getter) {
        // helper placeholder; real wiring done inline above
    }

    @Test
    void activatePrimesEveryRelation() {
        context.activate(island).join();

        verify(members).prime(island);
        verify(roles).prime(island);
        verify(permissions).prime(island);
        verify(warps).prime(island);
        verify(upgrades).prime(island);
        verify(flags).prime(island);
        verify(bans).prime(island);
        verify(coops).prime(island);
        assertThat(context.isActive(island)).isTrue();
    }

    @Test
    void activateIsIdempotent() {
        context.activate(island).join();
        context.activate(island).join();

        verify(members, times(1)).prime(island);
    }

    @Test
    void deactivateEvictsEveryRelation() {
        context.activate(island).join();

        context.deactivate(island);

        verify(members).evictIndex(island);
        verify(roles).evictIndex(island);
        verify(warps).evictIndex(island);
        verify(upgrades).evictIndex(island);
        verify(flags).evictIndex(island);
        verify(bans).evictIndex(island);
        verify(coops).evictIndex(island);
        assertThat(context.isActive(island)).isFalse();
    }
}
```

> The `wire(...)` helper above is an unused placeholder left from drafting — delete it and its single call before running. The real stubbing is the inline `lenient().when(...)` block. This test assumes each `*Service` exposes a `repository()` getter (Lombok `@Getter`) and that `AstralSkyblock` exposes `permissions()` — see Step 3's note.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=IslandContextServiceTest`
Expected: FAIL / compile error — `IslandContextService` missing; possibly `plugin.permissions()` missing.

- [ ] **Step 3: Implement `IslandContextService`**

> Prerequisite: confirm `AstralSkyblock` exposes a `permissions()` getter returning a `PermissionRepository` (or a `PermissionService`). The existing `PermissionRepository` is referenced by `RoleService`/role flows; if there is no `permissions()` getter yet, add a `PermissionRepository permissions` field constructed in `onEnable()` with a `@Getter`, mirroring the other services. Adjust the calls below to match whether permissions is exposed as a repository or via a service's `repository()`.

Create `src/main/java/com/astralrealms/skyblock/service/IslandContextService.java`:

```java
package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.astralrealms.skyblock.AstralSkyblock;

/**
 * Orchestrates the local cache footprint of an island. When an island's world becomes hosted on this
 * server, {@link #activate(UUID)} bulk-primes every relation (members, roles, permissions, warps,
 * upgrades, flags, bans, coops) into L1 + indexes, so the synchronous relationship accessors return
 * complete data. {@link #deactivate(UUID)} releases that footprint when the world unloads.
 */
public class IslandContextService {

    private final AstralSkyblock plugin;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public IslandContextService(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    /** Whether the island is primed locally. */
    public boolean isActive(UUID islandId) {
        return this.active.contains(islandId);
    }

    /**
     * Primes every relation of an island into the local cache. Idempotent: a second call while the
     * island is active is a no-op. On failure the active flag is cleared so a later call can retry.
     */
    public CompletableFuture<Void> activate(UUID islandId) {
        if (!this.active.add(islandId))
            return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> primed = CompletableFuture.allOf(
                this.plugin.members().repository().prime(islandId),
                this.plugin.roles().repository().prime(islandId),
                this.plugin.permissions().prime(islandId),
                this.plugin.warps().repository().prime(islandId),
                this.plugin.upgrades().repository().prime(islandId),
                this.plugin.flags().repository().prime(islandId),
                this.plugin.bans().repository().prime(islandId),
                this.plugin.coops().repository().prime(islandId)
        );

        return primed.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.getSLF4JLogger().error("Failed to prime island context for {}", islandId, throwable);
                this.active.remove(islandId);
            }
        });
    }

    /** Releases an island's local cache footprint (L1 only; L2/Redis stays warm). */
    public void deactivate(UUID islandId) {
        if (!this.active.remove(islandId))
            return;

        // Snapshot the island's role ids before clearing roles, so the role-keyed permission sets
        // can be evicted too.
        Collection<Long> roleIds = List.copyOf(this.plugin.roles().repository().getIslandRoleIds(islandId));

        this.plugin.members().repository().evictIndex(islandId);
        this.plugin.roles().repository().evictIndex(islandId);
        this.plugin.permissions().evict(roleIds);
        this.plugin.warps().repository().evictIndex(islandId);
        this.plugin.upgrades().repository().evictIndex(islandId);
        this.plugin.flags().repository().evictIndex(islandId);
        this.plugin.bans().repository().evictIndex(islandId);
        this.plugin.coops().repository().evictIndex(islandId);
    }
}
```

> If `MemberService`/`RoleService` do not already expose a `repository()` getter, add `@Getter` to those service classes (they already store a `repository` field) so `plugin.members().repository()` resolves. The new five services already expose it (Tasks 7-11).

- [ ] **Step 4: Wire it into `AstralSkyblock`**

Add field `private IslandContextService islandContext;` to the services block, and after `this.coops = new CoopService(this);` add:

```java
        this.islandContext = new IslandContextService(this);
```

Add the import.

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=IslandContextServiceTest`
Expected: BUILD SUCCESS, 3 tests passing.

```bash
git add src/main/java/com/astralrealms/skyblock/service/IslandContextService.java src/main/java/com/astralrealms/skyblock/AstralSkyblock.java src/test/java/com/astralrealms/skyblock/service/IslandContextServiceTest.java
git commit -m "feat: add IslandContextService priming/eviction lifecycle"
```

---

### Task 14: Lifecycle hooks in `WorldService` and `IslandService`

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/service/WorldService.java`
- Modify: `src/main/java/com/astralrealms/skyblock/service/IslandService.java`

**Interfaces:**
- Consumes: `plugin.islandContext().activate(UUID)` / `deactivate(UUID)`.

This task has no unit test (it's Bukkit-scheduler glue verified by the integration build); the accessors' tests in later tasks exercise the primed state.

- [ ] **Step 1: Activate on world load**

In `WorldService.loadWorld` (lines 129-147), inside the `Bukkit.getScheduler().runTask` body, after `this.loadedWorlds.put(id, instance);` and the `setHostServer` block, add the activation call before `future.complete(instance);`:

```java
                this.plugin.islandContext()
                        .activate(id)
                        .exceptionally(throwable -> {
                            plugin.getSLF4JLogger().error("Failed to activate island context for {}", id, throwable);
                            return null;
                        });
```

- [ ] **Step 2: Deactivate on world unload**

In `WorldService.unload(UUID uniqueId)` (lines 211-236), after `this.loadedWorlds.remove(uniqueId);` and before/around the `deleteHostServer` call, add:

```java
            this.plugin.islandContext().deactivate(uniqueId);
```

Also in `WorldService.unload()` (no-arg, lines 71-96) inside the `for` loop, after computing `UUID uniqueId = UUID.fromString(instance.getName());`, add `this.plugin.islandContext().deactivate(uniqueId);` so a full shutdown also releases contexts.

- [ ] **Step 3: Activate after island creation**

In `IslandService.create`, in the innermost success block where the world is created (after `player.teleportAsync(...)` at line 220, inside the `whenComplete` that logs "Island created"), add:

```java
                                                                    this.plugin.islandContext()
                                                                            .activate(island.uniqueId())
                                                                            .exceptionally(throwable2 -> {
                                                                                this.plugin.getSLF4JLogger().error("Failed to activate island context for {}", island.uniqueId(), throwable2);
                                                                                return null;
                                                                            });
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/astralrealms/skyblock/service/WorldService.java src/main/java/com/astralrealms/skyblock/service/IslandService.java
git commit -m "feat: prime/evict island context on world load and unload"
```

---

### Task 15: `Island` relationship accessors

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/model/island/Island.java`
- Test: `src/test/java/com/astralrealms/skyblock/model/island/IslandAccessorsTest.java`

**Interfaces:**
- Consumes: `plugin.warps().warps(id)`, `plugin.upgrades().upgrades(id)`, `plugin.flags().flags(id)`, `plugin.bans().bans(id)`, `plugin.coops().coops(id)`, `plugin.roles()...`, `AstralSkyblock.get()`.
- Produces: `Island.roles()`, `warps()`, `upgrades()`, `flags()`, `bans()`, `coops()` (in addition to existing `members()`/`owner()`).

Accessors delegate to `AstralSkyblock.get()`. The test sets the static singleton to a mock via the existing `instance` field (use reflection helper).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/model/island/IslandAccessorsTest.java`:

```java
package com.astralrealms.skyblock.model.island;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.service.WarpService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class IslandAccessorsTest {

    @AfterEach
    void tearDown() throws Exception {
        setInstance(null);
    }

    static void setInstance(AstralSkyblock plugin) throws Exception {
        Field field = AstralSkyblock.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, plugin);
    }

    @Test
    void warpsDelegatesToWarpService() throws Exception {
        UUID id = UUID.randomUUID();
        AstralSkyblock plugin = mock(AstralSkyblock.class);
        WarpService warps = mock(WarpService.class);
        IslandWarp warp = new IslandWarp(id, "home", 0, 0, 0, 0f, 0f, false, 0L);
        lenient().when(plugin.warps()).thenReturn(warps);
        lenient().when(warps.warps(id)).thenReturn(List.of(warp));
        setInstance(plugin);

        Island island = new Island(id, "Spawn", false, 0, 0, 0, 0, 0f, 0f, 0L, 0L);

        assertThat(island.warps()).containsExactly(warp);
    }
}
```

> Confirm `AstralSkyblock`'s singleton field is named `instance` (it is, per `AstralSkyblock.get()`); if `get()` reads a differently-named field, update `setInstance`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=IslandAccessorsTest`
Expected: FAIL / compile error — `Island.warps()` missing.

- [ ] **Step 3: Add the accessors**

In `Island.java`, after the existing `members()` method, add:

```java
    @Unmodifiable
    public Collection<IslandRole> roles() {
        return AstralSkyblock.get().roles().roles(this.uniqueId);
    }

    @Unmodifiable
    public Collection<IslandWarp> warps() {
        return AstralSkyblock.get().warps().warps(this.uniqueId);
    }

    @Unmodifiable
    public Collection<IslandUpgrade> upgrades() {
        return AstralSkyblock.get().upgrades().upgrades(this.uniqueId);
    }

    @Unmodifiable
    public Collection<IslandFlag> flags() {
        return AstralSkyblock.get().flags().flags(this.uniqueId);
    }

    @Unmodifiable
    public Collection<IslandBan> bans() {
        return AstralSkyblock.get().bans().bans(this.uniqueId);
    }

    @Unmodifiable
    public Collection<IslandCoop> coops() {
        return AstralSkyblock.get().coops().coops(this.uniqueId);
    }
```

Add imports: `IslandWarp`, `IslandUpgrade`, `IslandFlag` are in the same package (no import); `com.astralrealms.skyblock.model.member.IslandBan`, `com.astralrealms.skyblock.model.member.IslandCoop`, `com.astralrealms.skyblock.model.role.IslandRole`.

> `Island.roles()` calls `plugin.roles().roles(id)`. Add a `roles(UUID)` accessor to `RoleService` returning the island's cached roles sorted by weight DESC:
> ```java
>     @org.jetbrains.annotations.Unmodifiable
>     public java.util.Collection<com.astralrealms.skyblock.model.role.IslandRole> roles(java.util.UUID islandId) {
>         return this.repository.getIslandRoleIds(islandId).stream()
>                 .map(id -> this.repository.findCachedById(id).orElse(null))
>                 .filter(java.util.Objects::nonNull)
>                 .sorted(java.util.Comparator.comparingInt(com.astralrealms.model.role.IslandRole::weight).reversed())
>                 .toList();
>     }
> ```
> (Fix the `IslandRole` FQN to `com.astralrealms.skyblock.model.role.IslandRole`; use clean imports rather than inline FQNs in the real edit.)

Extend the placeholder `switch` in `Island.get(...)` with the new relations, mirroring the existing `members`/`owner` cases:

```java
            case "roles" -> ItemProvider.of(roles());
            case "warps" -> ItemProvider.of(warps());
            case "upgrades" -> ItemProvider.of(upgrades());
            case "flags" -> ItemProvider.of(flags());
            case "bans" -> ItemProvider.of(bans());
            case "coops" -> ItemProvider.of(coops());
```

- [ ] **Step 4: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=IslandAccessorsTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/model/island/Island.java src/main/java/com/astralrealms/skyblock/service/RoleService.java src/test/java/com/astralrealms/skyblock/model/island/IslandAccessorsTest.java
git commit -m "feat: add Island relationship accessors (roles/warps/upgrades/flags/bans/coops)"
```

---

### Task 16: `IslandMember` and `IslandRole` accessors

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/model/member/IslandMember.java`
- Modify: `src/main/java/com/astralrealms/skyblock/model/role/IslandRole.java`
- Test: `src/test/java/com/astralrealms/skyblock/model/member/IslandMemberAccessorsTest.java`

**Interfaces:**
- Consumes: `plugin.roles().repository().findCachedById(roleId)`, `plugin.players()...`, `plugin.islands().repository().findCachedById(islandId)`, `plugin.permissions().findCachedById(roleId)`.
- Produces: `IslandMember.role()`, `island()`, `hasPermission(String)`; `IslandRole.permissions()`, `members()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/model/member/IslandMemberAccessorsTest.java`:

```java
package com.astralrealms.skyblock.model.member;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandAccessorsSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class IslandMemberAccessorsTest {

    @AfterEach
    void tearDown() throws Exception {
        IslandAccessorsSupport.setInstance(null);
    }

    @Test
    void ownerHasEveryPermission() throws Exception {
        AstralSkyblock plugin = mock(AstralSkyblock.class);
        IslandAccessorsSupport.setInstance(plugin);

        IslandMember owner = new IslandMember(UUID.randomUUID(), UUID.randomUUID(), true, null, 0L);

        assertThat(owner.hasPermission("island.anything")).isTrue();
    }

    @Test
    void nonOwnerWithoutCachedRoleHasNoPermission() throws Exception {
        AstralSkyblock plugin = mock(AstralSkyblock.class);
        var permissions = mock(com.astralrealms.skyblock.repository.PermissionRepository.class);
        lenient().when(plugin.permissions()).thenReturn(permissions);
        lenient().when(permissions.findCachedById(7L)).thenReturn(java.util.Optional.empty());
        IslandAccessorsSupport.setInstance(plugin);

        IslandMember member = new IslandMember(UUID.randomUUID(), UUID.randomUUID(), false, 7L, 0L);

        assertThat(member.hasPermission("island.invite")).isFalse();
    }
}
```

Also create the small shared reflection helper `src/test/java/com/astralrealms/skyblock/model/island/IslandAccessorsSupport.java`:

```java
package com.astralrealms.skyblock.model.island;

import java.lang.reflect.Field;

import com.astralrealms.skyblock.AstralSkyblock;

public final class IslandAccessorsSupport {

    private IslandAccessorsSupport() {
    }

    public static void setInstance(AstralSkyblock plugin) throws Exception {
        Field field = AstralSkyblock.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, plugin);
    }
}
```

(Refactor Task 15's test to use this helper instead of its private copy when convenient; not required.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=IslandMemberAccessorsTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Add `IslandMember` accessors**

In `IslandMember.java`, add (with imports for `AstralSkyblock`, `IslandRole`, `Island`, `SkyblockPlayer`, `RolePermissions`, `Nullable`):

```java
    public @Nullable IslandRole role() {
        return this.roleId == null
                ? null
                : AstralSkyblock.get().roles().repository().findCachedById(this.roleId).orElse(null);
    }

    public @Nullable SkyblockPlayer player() {
        return AstralSkyblock.get().players().repository().findCachedById(this.playerUuid).orElse(null);
    }

    public @Nullable Island island() {
        return AstralSkyblock.get().islands().repository().findCachedById(this.islandId).orElse(null);
    }

    public boolean hasPermission(String permission) {
        if (this.isOwner)
            return true;
        if (this.roleId == null)
            return false;
        RolePermissions grants = AstralSkyblock.get().permissions().findCachedById(this.roleId).orElse(null);
        return grants != null && grants.has(permission);
    }
```

> Confirm `PlayerService` exposes `repository()` (add `@Getter` if missing) so `plugin.players().repository()` resolves, and that `plugin.permissions()` returns the `PermissionRepository` (added in Task 13's prerequisite). Adjust if permissions is wrapped in a service.

- [ ] **Step 4: Add `IslandRole` accessors**

In `IslandRole.java`, add (with imports for `AstralSkyblock`, `RolePermissions`, `IslandMember`, `Collection`, `UUID`, `Unmodifiable`):

```java
    public RolePermissions permissions() {
        return AstralSkyblock.get().permissions().findCachedById(this.id).orElse(null);
    }

    @Unmodifiable
    public Collection<IslandMember> members() {
        return AstralSkyblock.get().members().findIslandMembers(this.islandId).stream()
                .map(playerUuid -> AstralSkyblock.get().members().repository()
                        .findCachedById(new com.astralrealms.skyblock.model.member.MemberKey(this.islandId, playerUuid))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(member -> this.id.equals(member.roleId()))
                .toList();
    }
```

(Use proper imports for `MemberKey` and `Objects` in the real edit rather than inline FQNs.)

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=IslandMemberAccessorsTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/model/member/IslandMember.java src/main/java/com/astralrealms/skyblock/model/role/IslandRole.java src/test/java/com/astralrealms/skyblock/model/member/IslandMemberAccessorsTest.java src/test/java/com/astralrealms/skyblock/model/island/IslandAccessorsSupport.java
git commit -m "feat: add IslandMember and IslandRole relationship accessors"
```

---

### Task 17: `SkyblockPlayer` accessors + player-join priming

**Files:**
- Modify: `src/main/java/com/astralrealms/skyblock/model/member/SkyblockPlayer.java`
- Modify: `src/main/java/com/astralrealms/skyblock/service/MemberService.java`
- Modify: `src/main/java/com/astralrealms/skyblock/listener/PlayerConnectionListener.java`
- Test: `src/test/java/com/astralrealms/skyblock/model/member/SkyblockPlayerAccessorsTest.java`

**Interfaces:**
- Consumes: `MemberRepository.findByPlayer(UUID)` (existing), `MemberRepository.findPlayerIslands(UUID)` (player index), `plugin.islands().repository().findCachedById`.
- Produces: `SkyblockPlayer.membership()` → `Optional<IslandMember>`; `SkyblockPlayer.island()` → `Optional<Island>`; `MemberService.primePlayer(UUID)` → `CompletableFuture<Void>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/astralrealms/skyblock/model/member/SkyblockPlayerAccessorsTest.java`:

```java
package com.astralrealms.skyblock.model.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandAccessorsSupport;
import com.astralrealms.skyblock.repository.MemberRepository;
import com.astralrealms.skyblock.service.MemberService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class SkyblockPlayerAccessorsTest {

    @AfterEach
    void tearDown() throws Exception {
        IslandAccessorsSupport.setInstance(null);
    }

    @Test
    void membershipResolvesFromPlayerIndex() throws Exception {
        UUID island = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        AstralSkyblock plugin = mock(AstralSkyblock.class);
        MemberService members = mock(MemberService.class);
        MemberRepository repo = mock(MemberRepository.class);
        IslandMember member = new IslandMember(island, player, true, null, 0L);

        lenient().when(plugin.members()).thenReturn(members);
        lenient().when(members.repository()).thenReturn(repo);
        lenient().when(repo.findPlayerIslands(player)).thenReturn(List.of(island));
        lenient().when(repo.findCachedById(new MemberKey(island, player))).thenReturn(Optional.of(member));
        IslandAccessorsSupport.setInstance(plugin);

        SkyblockPlayer skyblockPlayer = new SkyblockPlayer(player, "Steve", 0L, 0L);

        assertThat(skyblockPlayer.membership()).contains(member);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=SkyblockPlayerAccessorsTest`
Expected: FAIL / compile error.

- [ ] **Step 3: Add `SkyblockPlayer` accessors**

In `SkyblockPlayer.java`, add (with imports for `AstralSkyblock`, `IslandMember`, `MemberKey`, `Island`, `Optional`):

```java
    public Optional<IslandMember> membership() {
        MemberRepository repository = AstralSkyblock.get().members().repository();
        return repository.findPlayerIslands(this.uniqueId).stream()
                .findFirst()
                .flatMap(islandId -> repository.findCachedById(new MemberKey(islandId, this.uniqueId)));
    }

    public Optional<Island> island() {
        return membership()
                .flatMap(member -> AstralSkyblock.get().islands().repository().findCachedById(member.islandId()));
    }
```

Add imports for `com.astralrealms.skyblock.repository.MemberRepository`, `com.astralrealms.skyblock.model.island.Island`.

- [ ] **Step 4: Add the player-prime helper and hook**

In `MemberService.java`, add:

```java
    /** Primes a player's single membership into L1 so their island can be resolved on any server. */
    public CompletableFuture<Void> primePlayer(UUID playerUuid) {
        return this.repository.findByPlayer(playerUuid).thenApply(ignored -> null);
    }
```

In `PlayerConnectionListener.onPlayerJoin`, after `this.plugin.players().load(event.getPlayer());`, add:

```java
        this.plugin.members()
                .primePlayer(event.getPlayer().getUniqueId())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to prime membership for {}", event.getPlayer().getName(), throwable);
                    return null;
                });
```

- [ ] **Step 5: Run the test (expect PASS) and commit**

Run: `mvn -q test -Dtest=SkyblockPlayerAccessorsTest`
Expected: BUILD SUCCESS.

```bash
git add src/main/java/com/astralrealms/skyblock/model/member/SkyblockPlayer.java src/main/java/com/astralrealms/skyblock/service/MemberService.java src/main/java/com/astralrealms/skyblock/listener/PlayerConnectionListener.java src/test/java/com/astralrealms/skyblock/model/member/SkyblockPlayerAccessorsTest.java
git commit -m "feat: add SkyblockPlayer accessors and prime membership on join"
```

---

### Task 18: Full build & integration verification

**Files:** none (verification only)

- [ ] **Step 1: Run the whole test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, every test green.

- [ ] **Step 2: Full package build**

Run: `mvn -q clean package`
Expected: BUILD SUCCESS; shaded jar produced. Resolve any unused-import/compile warnings surfaced by the build.

- [ ] **Step 3: Manual review against the spec**

Re-read the design's §6 accessor list and confirm each accessor exists and is local-only. Confirm `MemberService.findIslandMembers`/`findPlayerIslands` still compile (they predate this work) and now resolve against the migrated repository.

- [ ] **Step 4: Commit any cleanup**

```bash
git add -A
git commit -m "chore: build cleanup for island relationship caching"
```

(Skip if nothing changed.)

---

## Self-Review

**1. Spec coverage:**
- §4 `IndexedSyncedRepository` → Task 2. ✔
- §4.4 Member/Role migration → Tasks 3, 4. ✔
- §4.4 Permission island prime → Task 5. ✔
- §5 `IslandContextService` → Task 13; hooks → Task 14. ✔
- §6 accessors: Island → Task 15; Member/Role → Task 16; Player + join prime → Task 17. ✔
- §7 keys/packets/constants → Task 6; five repos+services → Tasks 7-11; wiring → Tasks 12, 13. ✔
- §10 tests → each task's test + Task 18 suite. ✔

**2. Placeholder scan:** Task 13's test contains an intentionally-flagged `wire(...)` placeholder with an explicit instruction to delete it before running; all other steps contain concrete code. Several steps include "confirm the core API / FQN" notes — these are verification guards against the one unavoidable unknown (exact core-library signatures), not deferred work.

**3. Type consistency:** `prime`/`evictIndex`/`keysIn`/`indexKeyOf`/`loadByIndex`/`onPrimed` names are used identically across the base (Task 2) and all consumers (Tasks 3-13). Composite keys `WarpKey`/`UpgradeKey`/`FlagKey`/`IslandPlayerKey` are consistent between models (Task 6), repositories (Tasks 7-11), and services. `IslandContextService.activate/deactivate/isActive` match between Task 13's implementation, its test, and the Task 14 hooks. Service `repository()` getters are required by Tasks 13/16/17 and noted as a prerequisite where a service may lack `@Getter`.

**Known external unknowns to verify during execution** (each flagged inline at first use): exact FQNs of `CacheService`/`MessagingService`/`DatabaseService`; `BinaryMessage` string read/write method names and re-readability in `PacketRoundTripTest`; whether `AstralSkyblock` already exposes `permissions()` and whether `MemberService`/`RoleService`/`PlayerService` expose `repository()`; the `CacheService.del` return type. None changes the design; each has an inline adjustment note.
