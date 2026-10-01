# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Java 25, Maven. Dependencies come from the private Astral Realms repository; locally the build runs offline against `~/.m2`.

```bash
mvn -o compile                                   # compile
mvn -o test                                      # all unit tests (JUnit 5 + Mockito)
mvn -o test -Dtest=RoleGrantTest                 # one test class
mvn -o test -Dtest=RoleGrantTest#ownerOnlyGrantsNeedTheOwner   # one test method
mvn -o clean package                             # shaded jar in target/ (what CI builds)
mvn -o compile -Dmaven.compiler.showDeprecation=true   # list deprecations
```

`mvn -q` hides the test summary; drop `-q` to see `Tests run:` lines. Incremental compiles can hide breakage after a dependency change — use `clean` then.

Tests only cover logic that needs no server, database or Redis (`src/test`). Pure helpers are made package-private (or get a static twin, e.g. `RoleService.grantAllowed`) so tests in the same package can reach them.

## Dependencies

- `core-paper` (AstralCore, sibling repo `../AstralCore`) provides storage (`DatabaseService`, `CrudRepository`), Redis (`CacheService`), RabbitMQ messaging (`MessagingService`), menus/dialogs/actions/requirements, transformers, ACF commands and the economy/teleport/chat services. Read its source when a behaviour depends on it.
- Lettuce is `provided`: AstralCore loads it at runtime; the plugin uses it directly only for `SET NX`/Lua scripts via `cache().runAsync(...)`.
- Paper API is `polaris-api`; Advanced Slime Paper (ASP) provides the island worlds.
- Lombok with **fluent accessors** (`src/main/java/.../lombok.config`): `island.uniqueId()`, not `getUniqueId()`. AstralCore uses the same convention.
- Do not touch `SQLAccessor` usages (`@Column(type = SQLAccessor.LONG_TIMESTAMP)`), even though it is deprecated.

## Architecture

A network skyblock: the plugin runs on every server (hubs included). Servers whose AstralCore group equals `islands-group` host island worlds; others only route players. Each island is its own ASP slime world named after the island UUID.

### Bootstrap (`AstralSkyblock#onEnable`)
Order matters: actions registered → configuration → database → Redis → messaging → services (repositories register their packet exchanges in their constructors, before `IslandService` exists, so packet handlers must tolerate `plugin.islands() == null`) → listeners (protection/upgrade listeners only on island servers) → placeholders. `loadConfiguration()` (also `/skyblock reload`) reloads YAML, blueprints, generators, upgrades and `transformers().load()`.

### Storage tiers (`repository/`)
- `SyncedRepository`: L1 Caffeine → L2 Redis → DB. **Only islands use L2** (`sharedCacheEnabled()`); members, roles, coops, bans, warps, upgrades and players are L1 + DB.
- `IndexedSyncedRepository` keeps a per-island index slice; `prime(islandId)` bulk-loads a slice and calls `onPrimed(island, removedKeys, values)` so subclasses only diff that island's slice.
- Loading an island runs `IslandRepository#cascade` (postLoad): roles, members, coops, bans, warps, settings, upgrades are attached as transient fields on `Island`. `refreshRelationships` is coalesced per island (one running, at most one queued). Targeted refreshes exist (`refreshUpgrades/Bans/Warps/Settings`).
- Never `save(island)` a whole row for a single-field change: use `IslandRepository#updateColumns`, which writes only those columns, applies the change to the *currently cached* instance, drops the L2 entry and publishes. Remote servers handle an island update by re-reading the one row (`onRemoteUpdate`), not the full cascade.
- Caps (members, coops, warps) are enforced inside the insert transaction under `SELECT ... FOR UPDATE` on the island row (`MemberRepository.lockIsland`).
- `schema.sql` is the only schema source: no migrations. Schema changes assume a wiped database (the project is in dev). AstralCore runs it at startup.

### Cross-server
- Messaging is RabbitMQ **fanout** (`MessagingService`): every packet reaches every server, the sender is echo-suppressed, and handlers run concurrently (no ordering). RPC requests must check they are addressed to this server (see `IslandLoadRequestPacket.serverId`).
- Island hosting (`ServerService`): hash `skyblock:islands:server` maps island → `server` or `server|loading`. Claim with `HSETNX` before loading, confirm/release with Lua compare scripts; dead hosts (no heartbeat in the `skyblock:servers` hash) are reclaimed on lookup; a starting server releases its own leftovers. Placement (`IslandService#host/place`) picks the emptiest server and retries elsewhere; creation goes through the same path with a blueprint id.
- Other Redis keys: `skyblock:island-tombstones:<id>` (deleted islands — saves and loads refuse them), `skyblock:island-arrivals:<id>` (spares an island from idle unload while a player is routed to it), single-server locks for coop expiry and invitation pruning.

### Worlds (`WorldService`)
`load()` is coalesced and idempotent; `delete()` marks the island in `deleted` (never saved again), tombstones it, broadcasts `IslandDeletePacket` and deletes storage after any pending save. Saves are snapshotted on the main thread (`getSerializableCopy`) and written on a dedicated executor; the host claim is released only after the write lands. The idle sweep unloads empty worlds after `world-idle-unload-seconds`.

### Threading
DB continuations run on Bukkit async threads, Redis ones on Lettuce threads, packet handlers on RabbitMQ virtual threads. Hop to the main thread (`runTask`) before touching Bukkit worlds/players. Custom events use `super(!Bukkit.isPrimaryThread())`, so they may be fired from async callbacks.

### Permissions model
Per-island roles (`island_roles`): custom MEMBER roles ranked by `weight`, plus one VISITOR and one COOP system role. The owner holds no role and bypasses checks (as does `skyblock.admin`). Non-members resolve through VISITOR, coops through COOP (`Island#hasPermission`). Rules in `RoleService`: editors may only grant what they hold; `ALL`/`SET_PERMISSION`/`SET_ROLE` are owner-only and never on system roles; disbanding is owner-only. Menu toggles of settings and role permissions are **per-editor pending edits** (`Island#toggleSetting(editor, …)`, `IslandRole#togglePermission(editor, …)`); enforcement only reads committed state, and changes are applied after the DB write succeeds. New `IslandPermission`/`IslandSettings` constants need an entry in `permissions.yml`/`settings.yml` (and grants in `roles.yml`).

Protection lives in `IslandPermissionsListener` (player actions → permissions) and `IslandSettingsListener` (environment → settings); an island world whose island is not cached denies.

### Configuration and UI resources
- Player-facing text is **French** (menus, dialogs, `messages.yml`, `settings.yml`, `permissions.yml`). `messages.yml` keys must match `ASMessages` constants (kebab-case of the enum name).
- Menus/dialogs are AstralCore YAML under `menus/` and `dialogs/`; custom actions are registered in `AstralSkyblock` (`registerAction`). Menus that receive an island must use `%parameters_island_...%` placeholders (resolved against the viewer), not `%skyblock_player_island_...%`.
- Lore logic uses AstralCore **transformers** (`transformers/*.yml`, applied with `$apply-transformer(id)`); `lore-modifiers` are deprecated.
- Only `database.properties` is copied from the jar; other config folders (menus, dialogs, blueprints, upgrades, generators, transformers) must be deployed by hand, and existing files are never overwritten.
- `block-values.yml` keys: material names, `#namespace:tag`, `*` patterns, `MOB_SPAWNER:<TYPE>`.
- New islands are cloned from a `.slime` file in `sourceWorlds/` named by a blueprint in `blueprints/`; the default blueprint must exist or creation is refused.

`REPORT.md` is the code audit (bugs, performance, unfinished features) that the recent commits worked through.
