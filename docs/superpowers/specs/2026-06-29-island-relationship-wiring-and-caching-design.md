# Island Relationship Wiring & Caching — Design

**Date:** 2026-06-29
**Status:** Approved (design) — pending implementation plan
**Scope:** Wire every island/member/role relationship to a synchronous, cache-backed accessor; guarantee the cache is fully primed whenever a relationship is read; build the missing repositories so the same guarantees cover warps, upgrades, flags, bans, and coops; keep the database and Redis off the hot path.

---

## 1. Background

The plugin runs across multiple Paper servers backed by a three-tier cache (`SyncedRepository`): L1 Caffeine (per server), L2 Redis (shared), L3 MySQL (source of truth). Each island's world is a SlimePaper world hosted on exactly **one** server at a time; that server is the authority for the island while it is loaded.

Relationship lookups ("all members of an island", "all roles of an island") are served from per-repository **secondary indexes** — `Multimap`s such as `MemberRepository.islandMembersMap` and `RoleRepository.islandRoleIndex` — that map an island id to the keys of its related rows. The relationship accessors must be **synchronous** because they are called from the Bukkit main thread (commands, placeholders, protection checks), which cannot block on I/O.

### 1.1 The problem

1. **Index population is path-dependent and incomplete.** The secondary indexes are populated only by `SyncedRepository.cacheLocally()`. But the three paths that actually load relationship data all **bypass** `cacheLocally()`:
   - the Caffeine async loader (normal read-through and the `refresh()` triggered by cross-server update packets) puts the loaded value straight into L1;
   - `MemberRepository.findByIsland()` / `RoleRepository.findByIsland()` call `cache.synchronous().put(...)` directly (Role separately calls `replaceValues` on its index; **Member does not index at all**);
   - member mutations (`add`/`addOwner`/`setRole`) go through `reload() → findById()`, i.e. the loader path.

   Net effect: `Island.members()` returns empty or partial results almost always, because nothing reliably fills `islandMembersMap`.

2. **No lifecycle primes the indexes.** `findByIsland()` exists as a bulk-prime primitive but is never invoked by any island load/activation flow.

3. **Five entities have no infrastructure.** `IslandWarp`, `IslandUpgrade`, `IslandFlag`, `IslandBan`, `IslandCoop` have model classes and database tables but **no repository, cache, index, service, or accessor**.

4. **Cross-server index coherency is an open TODO** (see `RoleRepository` javadoc): a `refresh()` on a remote update does not re-index, so an island's index drifts on servers other than the one that wrote.

### 1.2 Goal

`Island.members()`, `Island.roles()`, `Island.warps()`, … and the reverse accessors (`IslandMember.role()`, `IslandRole.permissions()`, `SkyblockPlayer.island()`, …) return **complete, correct** data synchronously, served entirely from memory, for any island that is **active on the local server**. The work to populate the cache is a bounded set of bulk queries run once when the island activates.

---

## 2. Design decisions (locked)

| Decision | Choice |
|---|---|
| Cache/lifecycle model | **Per-island active priming.** When an island's world loads here, bulk-prime all related entities + indexes into L1; evict on world unload. The hosting server is the authority. |
| Accessor API | **Synchronous, local-only.** Accessors read L1 + indexes. For an island not active on this server they return empty/`null` by design. |
| Remote reads | Commands/placeholders that must work against a non-hosted island opt into an **explicit async bulk load** (`repository.findByIsland(...)` / a service helper), not the sync accessors. |
| Relationship scope | **All** of them (see §6). |
| Missing repositories | **Build all five now** (warp, upgrade, flag, ban, coop). |
| Index strategy | **Shared `IndexedSyncedRepository` base** (Approach B) — index maintained on every L1 path; existing Member/Role repos migrated onto it. |

### Non-goals

- No change to async API of mutating operations (`save`/`delete`/`add`/`setRole` stay `CompletableFuture`).
- No routing of mutations to the hosting server; cross-server correctness is achieved through index-on-refresh, not request routing.
- No new gameplay features (no new commands beyond what's needed to demonstrate accessors; the existing `/sb info` is the reference consumer).
- No schema changes — all eight tables already exist.

---

## 3. Architecture overview

```
                       ┌─────────────────────────────────────────┐
   WorldService.loadWorld() success ──activate(islandId)──►       │
   IslandService.create() success    ──activate(islandId)──► IslandContextService
   WorldService.unload() success     ──deactivate(islandId)─►     │
                       └───────────────┬─────────────────────────┘
                                       │ parallel bulk prime / evict
        ┌──────────┬──────────┬────────┼────────┬─────────┬─────────┬─────────┐
     MemberRepo  RoleRepo  PermissionRepo  WarpRepo UpgradeRepo FlagRepo BanRepo CoopRepo
        └──────────┴──────────┴────────────────────────────────────────────────┘
                                       │  (all extend)
                          IndexedSyncedRepository<K,V,I>  ── owns Multimap<I,K> index,
                                       │                      maintained on EVERY L1 path
                                SyncedRepository<K,V>     ── L1 Caffeine / L2 Redis / L3 MySQL

   Sync accessors (main thread):
     Island.members()/roles()/warps()/… ─► service ─► repo.keysIn(islandId) ─► repo.findCachedById(key)
     IslandMember.role()/player()/island() ─► repo.findCachedById(...)
```

---

## 4. `IndexedSyncedRepository<K, V, I>` (new base)

A subclass of `SyncedRepository<K, V>` that maintains a single secondary index `Multimap<I, K>` mapping an **index key** `I` (here always `UUID islandId`) to the cache keys `K` of the values belonging to it.

### 4.1 Responsibilities & contract

- **`protected abstract I indexKeyOf(V value)`** — extracts the index key (island id) from a value.
- **`protected abstract CompletableFuture<List<V>> loadByIndex(I indexKey)`** — the bulk query (`SELECT … WHERE island_id = ?`). Each repo implements it.
- **`public @Unmodifiable Collection<K> keysIn(I indexKey)`** — snapshot copy of the index slice (used by accessors).
- **`public CompletableFuture<List<V>> prime(I indexKey)`** — runs `loadByIndex`, then **atomically replaces** the index slice for `indexKey` and inserts each value into L1 via `cacheLocally` (so L1 + index move together). Returns the loaded values. This is the lifecycle prime primitive.
- **`public void evictIndex(I indexKey)`** — invalidates every key currently in the slice from **L1 only** (`invalidateLocally`) and clears the slice. Used on deactivation. (L2 is shared and stays warm for whichever server next hosts the island.)

### 4.2 The fix: index maintained on every L1 path

The base guarantees the index reflects L1 contents regardless of how a value entered L1:

1. **Loader / `refresh()` path** — the base wraps the async cache loader so that after `load(key)` resolves to a non-null `V`, it calls `indexValue(v)` before the value is handed to Caffeine. This is the single change that closes both the "members empty" bug and the cross-server coherency TODO: a `refresh()` from a remote update now re-indexes, and a `refresh()` of a never-seen key (remote insert) loads, indexes, and inserts.
2. **Write-through (`cacheLocally`)** — overridden to call `super.cacheLocally(value)` then `indexValue(value)`.
3. **Eviction** — a `RemovalListener` removes the key from its slice (matching today's Member/Role listeners). Handles size-eviction and `invalidate`.
4. **`invalidateLocally`** — overridden to remove from the slice as a fallback for cases where the eviction listener lacks the value.

`indexValue(V)` is idempotent (`HashMultimap` dedupes), so overlapping paths are safe.

### 4.3 Why this is correct for completeness

The index for an active island is **complete** because `prime(islandId)` loads the full row set in one query and replaces the slice atomically. After that, incremental loads/refreshes keep it in sync, and the Member/Role/etc. caches are **unbounded** (no `maximumSize`), so an active island's entries are never size-evicted out from under the index. Only explicit invalidation removes entries, and those paths de-index.

### 4.4 Migration of existing repositories

- **`MemberRepository`** → extend `IndexedSyncedRepository<MemberKey, IslandMember, UUID>`. Replace the hand-rolled `islandMembersMap` with the base index. **Keep** `playerIslandsMap` as a *second* index local to `MemberRepository` (player→island) — the base supports one primary index; the player index is maintained by overriding `indexValue`/eviction in `MemberRepository` (small, explicit). `findByIsland` becomes a thin wrapper over `prime`.
- **`RoleRepository`** → extend `IndexedSyncedRepository<Long, IslandRole, UUID>`. Drop `islandRoleIndex` and its bespoke maintenance; `getIslandRoleIds` → `keysIn`. `findByIsland` → wraps `prime` (preserving the `ORDER BY weight DESC, id` for the returned list; index ordering is irrelevant since accessors re-sort).
- **`PermissionRepository`** → keyed by role id, indexed by **island id** is not natural (permissions join through roles). It stays on `SyncedRepository` but gains an island-scoped prime: `prime(islandId)` reuses the existing `findByIsland(islandId)` query (join on `island_roles`) and inserts results into L1. It does **not** maintain an island index (permission lookups are always by role id, which the caller already has from the role). `IslandContextService` calls `PermissionRepository.findByIsland(islandId)` directly.
- **`IslandRepository`** stays as-is (its `nameIslandMap` is a name index, not an island-children index; out of scope to migrate, and `Island` is keyed by its own id).

---

## 5. `IslandContextService` (new lifecycle orchestrator)

Owns activation/deactivation of an island's local cache footprint.

```
activate(UUID islandId): CompletableFuture<Void>
  if already active (local guard set) -> return completed
  mark active
  return CompletableFuture.allOf(
      members.prime(islandId),
      roles.prime(islandId),
      permissions.findByIsland(islandId),   // primes role-keyed perms
      warps.prime(islandId),
      upgrades.prime(islandId),
      flags.prime(islandId),
      bans.prime(islandId),
      coops.prime(islandId)
  ).whenComplete(log + on failure clear the active guard so a retry can re-prime)

deactivate(UUID islandId): void
  unmark active
  members.evictIndex(islandId); roles.evictIndex(islandId);
  warps.evictIndex(islandId); upgrades.evictIndex(islandId);
  flags.evictIndex(islandId); bans.evictIndex(islandId); coops.evictIndex(islandId)
  // permissions: evict the role-keyed entries for the island's roles (resolved from roles slice before it is cleared) — order: snapshot role ids, evict perms, then evict roles
```

- **Idempotency:** a `Set<UUID> active` (or a concurrent guard) prevents double-priming when both `loadWorld` and a redundant trigger fire.
- **Active set** also gives accessors/commands a cheap "is this island live here?" check if needed.

### Hook points

| Trigger | Call |
|---|---|
| `WorldService.loadWorld(id, world)` succeeds (`loadedWorlds.put`, `setHostServer`) | `plugin.islandContext().activate(id)` — chained into the returned future so spawn completes only after prime |
| `IslandService.create(...)` after owner+roles+world created | `activate(island.uniqueId())` (the world is loaded locally on the creating server) |
| `WorldService.unload(uniqueId)` succeeds (`loadedWorlds.remove`, `deleteHostServer`) | `plugin.islandContext().deactivate(uniqueId)` |

`WorldService` gains a dependency on `IslandContextService` via the existing `plugin` accessor (no constructor wiring change beyond the new getter). Priming failures are logged and do not abort world load (accessors degrade to empty, exactly as a cold cache does today), and the active guard is cleared so the next access/trigger can retry.

---

## 6. Relationship accessors (synchronous, local-only)

All accessors read indexes + `findCachedById`; they never touch DB/Redis and return empty/`null` when the island is not active locally.

### `Island`
- `Collection<IslandMember> members()` → `members.keysIn(id)` → `findCachedById`
- `IslandMember owner()` → `members().stream().filter(isOwner).findFirst()`
- `Collection<IslandRole> roles()` → `roles.keysIn(id)` → `findCachedById`, sorted by `weight DESC`
- `Collection<IslandWarp> warps()`
- `Collection<IslandUpgrade> upgrades()`
- `Collection<IslandFlag> flags()`
- `Collection<IslandBan> bans()`
- `Collection<IslandCoop> coops()`
- Placeholder cases extended for the new relations (mirroring the existing `members`/`owner` cases).

### `IslandMember`
- `IslandRole role()` → `roleId == null ? null : roles.findCachedById(roleId)`
- `SkyblockPlayer player()` → `players.findCachedById(playerUuid)`
- `Island island()` → `islands.findCachedById(islandId)`
- `boolean hasPermission(String permission)` → owner ⇒ `true`; else `permissions.findCachedById(roleId)` ⇒ `has(permission)`

### `IslandRole`
- `RolePermissions permissions()` → `permissions.findCachedById(id)`
- `Collection<IslandMember> members()` → island's members filtered by `roleId == id`

### `SkyblockPlayer`
- `Optional<IslandMember> membership()` → `members.findPlayerIslands(uuid)` (player index) → single `findCachedById`
- `Optional<Island> island()` → `membership().map(m -> islands.findCachedById(m.islandId()))`

Accessors live on the model classes (matching the existing `Island.members()` pattern) and delegate to services via `AstralSkyblock.get()`. Model classes stay logic-free beyond delegation.

### Per-player priming

`PlayerConnectionListener` already calls `players().load(player)`. It additionally triggers (fire-and-forget, logged on failure) a prime of the player's **own** membership so `SkyblockPlayer.island()` works on the lobby/any server even when the island world is hosted elsewhere: load `members.findByPlayer(uuid)` into L1. (This is a single row, not a full island prime — it does not make `island.members()` complete off-host, by design.)

---

## 7. New repositories, services, keys, packets

For each of warp/upgrade/flag/ban/coop, following the established pattern:

### Composite keys (records, like `MemberKey`)
- `WarpKey(UUID islandId, String name)`
- `UpgradeKey(UUID islandId, String upgrade)`
- `FlagKey(UUID islandId, String flag)`
- Bans/coops are `(islandId, playerUuid)` — reuse a shared `IslandPlayerKey(UUID islandId, UUID playerUuid)` record (or `MemberKey`-shaped; new record to avoid semantic coupling to membership).

### Repositories (extend `IndexedSyncedRepository<Key, Value, UUID>`)
Each implements: `keyFromValue`, `indexKeyOf` (→ `islandId`), `cacheKey`, `loadById`, `loadByIndex` (`SELECT … WHERE island_id = ?`), `saveToDatabase` (upsert), `deleteFromDatabase`, `publishUpdate`, `publishInvalidation`. Hand-mapped `ResultSet` rows (these models carry no `@Id`), mirroring `MemberRepository.map`.

Mutation methods per repo as needed by callers (e.g. `WarpRepository.set/remove`, `FlagRepository.set`, `UpgradeRepository.setLevel`, `BanRepository.ban/unban`, `CoopRepository.add/remove`). The implementation plan will enumerate the minimal set; gameplay commands using them are out of scope for this spec.

### Services (thin)
`WarpService`, `UpgradeService`, `FlagService`, `BanService`, `CoopService` — expose the island-scoped read used by `Island` accessors plus the mutations above. Wired in `AstralSkyblock.onEnable()` after the infrastructure services, with `@Getter` accessors (`warps()`, `upgrades()`, `flags()`, `bans()`, `coops()`, `islandContext()`).

### Packets (cross-server invalidation)
Two generic pairs registered in `ASPacketRegistry` at the next free repository ids (`0x06+`):
- `IslandStringKeyUpdatePacket` / `…DeletePacket` `(UUID islandId, String key)` — for warp/upgrade/flag.
- `IslandPlayerKeyUpdatePacket` / `…DeletePacket` `(UUID islandId, UUID playerUuid)` — for ban/coop (same wire shape as the member packets but on their own channels).

New cache keys + update channels added to `ASConstants` (`WARP_*`, `UPGRADE_*`, `FLAG_*`, `BAN_*`, `COOP_*`), following the `skyblock:<x>` / `skyblock.<x>.update` convention.

---

## 8. Data & cache flow

**Activation (island world loads on server S):**
```
loadWorld success → activate(id)
  → 8 parallel `SELECT … WHERE island_id = ?` (one per relation; permissions joins roles)
  → each row → cacheLocally → L1 + index slice (atomic replace per relation)
  → done: every relation accessor on S now returns complete data from memory
```

**Hot path (main thread accessor):**
```
island.members() → members.keysIn(id) [in-memory] → findCachedById(key) [in-memory] → list
   zero DB, zero Redis
```

**Mutation on host S:** repo `save`/`delete` → DB write → L1+L2 write-through (`cacheLocally` re-indexes) → publish update/invalidate.

**Mutation on another server R (e.g. admin acts on a remote island):** R writes DB + L2, publishes packet → S's handler `refresh(key)` → loader reloads from L2/DB → **re-indexes** (the §4.2 fix) → S's accessors reflect the change. New rows arrive via `refresh` of an absent key, which loads+indexes.

**Deactivation (world unloads on S):** `evictIndex` per relation → `invalidateLocally` each key (L1 only) + clear slices. L2 stays warm for the next host.

---

## 9. Error handling

- **Prime failure** (DB/Redis hiccup during `activate`): logged; the relation degrades to empty (same as a cold cache); the island's active guard is cleared so a later trigger/access can re-prime. World load is **not** aborted by a prime failure.
- **Accessor on cold/inactive island:** returns empty/`null` — never throws, never blocks.
- **Null `findCachedById`** (entry evicted mid-iteration): filtered out (`Objects::nonNull`), as the current `MemberService` already does.
- **L2/Redis write failures** in the base are already logged and swallowed; unchanged.

---

## 10. Testing strategy

- **`IndexedSyncedRepository` (unit, fakes for DB/cache/messaging):**
  - index populated via loader path (the core regression): `findById` of a row makes `keysIn` contain it.
  - `refresh()` of a remote update re-indexes; `refresh()` of an absent key loads+indexes.
  - `prime` replaces the slice atomically and primes L1.
  - eviction + `invalidateLocally` de-index.
  - `evictIndex` clears L1 (local) and slice without touching L2.
- **`IslandContextService`:** `activate` primes all relations (assert each accessor returns complete data); idempotent on double-activate; `deactivate` clears; prime failure clears the guard.
- **Accessors:** `Island.members()/roles()/warps()/…` return primed data; return empty when inactive; `IslandMember.hasPermission` (owner short-circuit + role grant); `SkyblockPlayer.island()` via player index.
- **Migrated repos:** existing Member/Role behavior preserved (owner lookup, role defaults, ordering); the previously-broken `Island.members()` now returns the full set after activation.
- **Regression guard:** a test asserting `Island.members()` is non-empty after `activate`, which fails against today's code.

---

## 11. File-by-file change summary

**New**
- `repository/IndexedSyncedRepository.java`
- `repository/WarpRepository.java`, `UpgradeRepository.java`, `FlagRepository.java`, `BanRepository.java`, `CoopRepository.java`
- `service/WarpService.java`, `UpgradeService.java`, `FlagService.java`, `BanService.java`, `CoopService.java`
- `service/IslandContextService.java`
- `model/island/WarpKey.java`, `UpgradeKey.java`, `FlagKey.java`; `model/member/IslandPlayerKey.java`
- `messaging/packet/repository/IslandStringKeyUpdatePacket.java`, `…DeletePacket.java`, `IslandPlayerKeyUpdatePacket.java`, `…DeletePacket.java`
- Test classes per §10.

**Modified**
- `repository/MemberRepository.java`, `RoleRepository.java` — migrate to `IndexedSyncedRepository`; `PermissionRepository.java` — add island-scoped prime helper if not already sufficient.
- `model/island/Island.java` — add `roles/warps/upgrades/flags/bans/coops` accessors + placeholder cases.
- `model/member/IslandMember.java` — add `role/player/island/hasPermission`.
- `model/role/IslandRole.java` — add `permissions/members`.
- `model/member/SkyblockPlayer.java` — add `membership/island`.
- `service/MemberService.java` — accessor helpers already present; align to base method names.
- `service/WorldService.java` — `activate`/`deactivate` hooks in `loadWorld`/`unload`.
- `service/IslandService.java` — `activate` after `create`.
- `listener/PlayerConnectionListener.java` — prime player's own membership.
- `messaging/ASPacketRegistry.java` — register new packets at `0x06+`.
- `utils/ASConstants.java` — new cache keys + channels.
- `AstralSkyblock.java` — construct + expose new services.

---

## 12. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Migrating Member/Role introduces a regression | Characterization tests on existing behavior before refactor; the migration is mechanical (same index semantics, fewer code paths). |
| `prime` adds latency to world load (8 queries) | Run in parallel; queries are single-index lookups on indexed columns; world load is already async and multi-second. |
| Memory growth from unbounded relation caches | Bounded by *active* islands (one server hosts a finite set of worlds); `deactivate` releases L1 on unload. Revisit `maximumSize` only if profiling shows pressure. |
| Player index (`playerIslandsMap`) maintenance in migrated `MemberRepository` | Keep it as an explicit secondary index in `MemberRepository`, maintained alongside the base index; covered by tests. |
| Cross-server mutation on an absent key not re-indexing | §4.2 wraps the loader so `refresh()`/`load()` of any key indexes it; explicitly tested. |

---

## 13. Open questions

None blocking. Mutation command surfaces (e.g. `/island warp set`) that *consume* the new repositories are intentionally out of scope and can be specced separately.
