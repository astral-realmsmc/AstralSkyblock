# AstralSkyblock: Code Audit Report

- **Commit audited:** `294682b` (master)
- **Date:** 2026-10-01
- **Method:** 8 parallel review agents, each covering one slice of the code:
  1. Persistence
  2. Messaging and world lifecycle
  3. Island and social services
  4. Gameplay services
  5. Commands, UI and placeholders
  6. Listeners and bootstrap
  7. Cross-cutting concurrency and performance
  8. Unfinished features
- **How findings were checked:** agents reported a finding only after confirming it in the code. Where findings overlapped, I merged them. I re-checked the main Critical items in the source myself.
- **Not done:** nothing was run, and there is no test suite.
- **Paths:** relative to `src/main/java/com/astralrealms/skyblock/` unless they start with `resources/`.

## Summary

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Bugs (incl. exploits) | 9 | 19 | 34 | 30 | 92 |
| Performance | 0 | 4 | 6 | 9 | 19 |
| Unfinished features | — | 4 | 10 | 17 | 31 |
| Security / config | 1 | — | 1 | — | 2 |

**Overall picture**
- **Main storage and caching layer.** The design is sound: L1/L2 caching, a cascade loader, and conditional UPDATEs for upgrade purchases. Several invariants do not hold across servers, though:
  - island hosting is not exclusive;
  - settings are not synced;
  - name indexes go stale;
  - saves overwrite the whole row, so one server's save can revert another's change.
- **Protection.** It covers roughly a third of the ways a visitor can affect someone else's island. The commit message "every declared permission is enforced" is true, but the permission list itself is missing containers, doors, buckets, entities and more.
- **Fresh install fails.** On a fresh install the plugin **does not enable**, because the default blueprint uses an outdated format (B-C3).

---

## Fix these first

| # | ID | Problem | Why it matters |
|---|---|---|---|
| 1 | SEC-1 | MySQL credentials are committed in `loader.yml` | Rotate them now |
| 2 | B-C3 | The shipped `blueprints/default.yml` crashes enable | The plugin does not start on a fresh install |
| 3 | B-C1 | Every server handles every island load request | Several copies of one island exist, and one overwrites the others' progress |
| 4 | B-C2 | Epoch millis are written to and read from `TIMESTAMP(3)` columns | Bans, coops and warps fail, and any existing row breaks island loading |
| 5 | B-C4 | The mob-drop upgrade duplicates the contents of donkey and llama chests | Unlimited item duplication |
| 6 | B-C5/C6 | Visitors can open containers, use doors and redstone, and use buckets | Griefing and theft on every island |
| 7 | B-H1 | Role editors can give VISITOR `DISBAND_ISLAND` or `ALL` | Any player on the network can then delete the island |
| 8 | B-C7 | An ownership transfer can commit with no owner | Needs a manual DB repair |
| 9 | B-C8 | The coop index is never filled from DB loads | After a restart, coops can't be removed |
| 10 | B-H2/H3 | No atomic hosting claim, and host mappings never expire | Two servers can host one island, and islands become unreachable after a crash |

---

# 1. Security / configuration

### SEC-1 (Critical): Database credentials are committed and shipped in the jar
- **Where:** `resources/loader.yml:4-5`. They have been in git history since `9483646`. `resources/database.properties` also ships the test credentials `testuser`/`test623`.
- **Impact:** anyone with access to the repo or the jar can reach the slime-world MySQL. Every fresh deployment silently points at that database.
- **Fix:**
  - Rotate the credential.
  - Ship placeholders, or read the credentials from `AstralPaperAPI.credentialsProvider()` or environment variables.
  - Consider purging the credentials from git history.

### SEC-2 (Medium): `loader.yml` `useSsl` is never bound, so SSL is silently off
- **Where:** `resources/loader.yml:7`, `configuration/ASPLoaderConfiguration.java:6`.
- **Problem:** Configurate binds keys as `LOWER_CASE_DASHED`, so the record component maps to `use-ssl`, not `useSsl`. The value falls back to `false`.
- **Fix:** rename the key to `use-ssl`, or annotate the component with `@Setting("useSsl")`.

---

# 2. Bugs

## Critical

### B-C1. Every server handles island load requests, not just the target
- **Where:** `service/IslandService.java:56-90` (the handler never reads `request.serverId()`), `messaging/packet/island/IslandLoadRequestPacket.java:19`.
- **Problem:**
  - Messaging runs over RabbitMQ **fanout** exchanges, so every server receives every request.
  - Every island server and every hub calls `worlds().load(island)`.
  - Each server then `HSET`s itself as the host (last writer wins) and replies.
- **Impact:**
  - Several live copies of one island exist, and each is saved independently, so the last save overwrites the owner's progress.
  - Hubs have no protection listeners and no idle sweep, so their copies can be griefed freely and stay in memory until restart.
- **Fix:** at the top of the handler, add `if (!request.serverId().equals(localId) || !isIslandServer()) return;`. Better: route RPCs to a per-server queue. Also make hosting atomic (B-H2).

### B-C2. Epoch-millis `long` is written to and read from `TIMESTAMP(3)` columns (coops, bans, warps)
- **Where:**
  - `repository/CoopRepository.java:143/222`
  - `repository/BanRepository.java:146/247`
  - `repository/WarpRepository.java:149/218`
  - columns at `resources/schema.sql:208,228,258`
- **Problem:**
  - **Write:** the code binds `setLong(System.currentTimeMillis())`. Under MariaDB strict mode, the default, the INSERT fails with "Incorrect datetime value". In non-strict mode it stores `0000-00-00`.
  - **Read:** `getLong("created_at")` always throws. This was verified by disassembling mariadb-java-client 3.5.9: `TimestampColumn.decodeLong*` throws unconditionally.
- **Impact:**
  - `/is ban`, coop accept and `/is setwarp` fail with UNEXPECTED_ERROR.
  - Any existing row makes the island cascade throw, which breaks island load and the startup warmup (B-H16).
- **Fix:** use `setTimestamp(new Timestamp(ms))` and `getTimestamp(..).getTime()`, the same as `MemberRepository` does for `joined_at`. Or omit the column and let `DEFAULT CURRENT_TIMESTAMP(3)` fill it.

### B-C3. The shipped default blueprint doesn't match the model, so the plugin fails to enable
- **Where:** `resources/blueprints/default.yml` (`schematic: "default.schem"`), `model/IslandBlueprint.java`, `service/BlueprintService.java:47`.
- **Problem:**
  - The record expects `source-world` and `spawn-location`, but the file has neither, so `sourceWorld` is null and `sourceWorldsFolder.resolve(null)` throws an NPE during enable.
  - If only the key is fixed, a null `spawnLocation` still throws an NPE on every `/is create` (`IslandService.java:189`, `WorldService.createNewWorld`).
  - If the file is skipped instead, `"No default blueprint found"` aborts enable.
  - No `.slime` source world ships with the plugin.
- **Fix:**
  - Rewrite the file as `source-world:` plus a `spawn-location: {x,y,z,yaw,pitch}` block.
  - Validate each blueprint and skip bad ones with a warning.
  - Document or ship the source world.

### B-C4. The mob-drop upgrade duplicates items stored in or carried by entities
- **Where:** `listener/UpgradeEffectsListener.java:250-271`. This contradicts `resources/upgrades/mob-drops.yml:5-6`.
- **Problem:** the listener multiplies **every** stack in `EntityDeathEvent#getDrops()` for any non-player entity. That includes:
  - donkey, mule and llama chest contents;
  - saddles and horse armour;
  - allay and fox held items;
  - items mobs picked up;
  - armor stands and their equipment.
- **Impact:** fill a donkey with 32-stacks of diamond blocks and kill it on an island with ×2 drops, and you get 64-stacks back. The trick can be repeated without limit.
- **Fix:** multiply only loot-table output, for example in `LootGenerateEvent` for entity kills. Or skip `AbstractHorse`, `ArmorStand`, `Allay`, `Fox` and any mob with `getCanPickupItems()`, and exclude items that match the entity's equipment or inventory.

### B-C5. Containers, doors, redstone and other block interactions are not protected
- **Where:**
  - `listener/IslandPermissionsListener.java` (the whole file)
  - `model/role/IslandPermission.java`
  - `utils/SkyblockTags.java:13-24`: `CONTAINERS` and `MECHANISMS` are defined but never used.
- **Problem:**
  - `InventoryOpenEvent` is checked only for minecart holders.
  - The only `PlayerInteractEvent` handler is the brush check.
  - No permission exists for any of these interactions.
- **Impact:** a VISITOR with no permissions can:
  - empty chests, barrels, shulkers, furnaces and hoppers;
  - flip levers and buttons;
  - open doors;
  - take jukebox discs;
  - re-time repeaters.
- **Fix:**
  - Add the permissions `CONTAINER_OPEN`, `USE_DOORS` and `USE_REDSTONE` (or similar).
  - Enforce them in `PlayerInteractEvent` (RIGHT_CLICK_BLOCK) and `InventoryOpenEvent`, using `getHolder(false)`.
  - Grant them to MEMBER and COOP in `roles.yml`.

### B-C6. Buckets are not protected
- **Where:** `listener/IslandPermissionsListener.java`. No handler for `PlayerBucketEmptyEvent`, `PlayerBucketFillEvent` or `PlayerBucketEntityEvent` exists anywhere in the codebase.
- **Impact:** a visitor without BUILD can pour lava over a wooden base, and a visitor without BREAK can steal the generator's water source.
- **Fix:** gate emptying on BUILD and filling on BREAK, or add a dedicated `BUCKET` permission.

### B-C7. Ownership transfer can leave an island with no owner
- **Where:** `repository/MemberRepository.java:180-201`, `service/MemberService.java:418-451`.
- **Problem:**
  - The demote and promote UPDATEs never check their affected-row counts.
  - `transfer` never checks that `newOwner` still belongs to this island.
  - `DatabaseService.transaction` swallows exceptions.
- **Impact:**
  1. The target member leaves, or is kicked, on another server while the owner's transfer menu is open.
  2. The owner clicks the member: the demote succeeds and the promote matches 0 rows.
  3. The transaction commits and the island has no owner.
  4. Nobody can disband or transfer the island, and repairing it needs manual DB work.
- **Fix:**
  - Throw (which rolls back) unless each UPDATE affects exactly 1 row.
  - Validate that `newOwner` belongs to this island and is not the current owner.
  - Apply the same row-count check to `RoleRepository.setDefault`.

### B-C8. Coop index is never filled from DB loads, so persisted coops can't be removed
- **Where:** `repository/CoopRepository.java:58-62`, `repository/IndexedSyncedRepository.java:96-104`, `service/CoopService.java:94`.
- **Problem:** `prime()` (used by warmup and every cascade) never calls `index()`. `CoopRepository` has no `onPrimed` override, unlike `BanRepository:216` and `MemberRepository:314`.
- **Impact:**
  - After any restart, `/is uncoop` and the uncoop menu action return `COOP_NOT_FOUND` for every existing coop.
  - `SkyblockAPI.isCoop` returns false for them.
- **Fix:** override `onPrimed` the same way `BanRepository` does. Better: have `CoopService.remove` use `island.findCoop(...)`.

### B-C9. Disbanding deletes only the `islands` row, which conflicts with the schema's FK design *(needs one integration test to confirm)*
- **Where:** `service/IslandService.java:277` → `CrudRepository.delete`. Schema: `resources/schema.sql:55,139,187,189` and `queries.sql:569-584`.
- **Problem:**
  - `island_roles` cascades from `islands`.
  - `island_members.role_id → island_roles` is `ON DELETE RESTRICT`.
  - `island_members` also cascades from `islands`.
  - The schema says to delete members first, but no code does this.
- **Impact:** on islands with non-owner members, disband can fail with "Cannot delete or update a parent row". Whether it fails depends on the order InnoDB processes the cascades.
- **Fix:** in one transaction, run `DELETE FROM island_members WHERE island_id=?` and then delete the island.

## High

### B-H1. Role editors can grant permissions they don't hold, so any player can end up able to delete the island
- **Where:**
  - `action/island/role/ToggleRolePermissionAction.java:22-35`
  - `service/RoleService.java:85-115`
  - `service/IslandService.java:271-275`
  - `command/context/IslandContextResolver.java:27-30`
  - `resources/roles.yml`
- **Problem:**
  - Editing a role only requires `SET_PERMISSION` and outranking the role. Nothing limits *which* permissions are granted, so `ALL` and `DISBAND_ISLAND` can be given to VISITOR (weight 0) or COOP (weight 1).
  - Non-members are checked through the VISITOR role.
  - `/is delete <island>` accepts any island by name and is gated only by `DISBAND_ISLAND`.
  - The shipped Admin role has `ALL`, so it can disband the island too, and there is no confirmation.
- **Impact:** a moderator, or an owner who mis-clicks, grants `DISBAND_ISLAND` to Visitor. Any player on the network can then delete the island, with no undo. Kick, ban, settings and permission changes are opened up in the same way.
- **Fix:**
  - Only allow granting permissions the editor holds.
  - Never allow `ALL`, `DISBAND_ISLAND`, `SET_PERMISSION` or `SET_ROLE` on VISITOR or COOP.
  - Make disband owner-only (or `skyblock.admin`).
  - Add a confirmation step to `/is delete`.

### B-H2. Nothing stops two servers from hosting the same island
- **Where:** `service/IslandService.java:127-171`, `service/ServerService.java:46-62`, `service/WorldService.java:282-287,413-418`.
- **Problem:**
  - Placement is check-then-act: `HGET`, then choose the emptiest server, then load, then `HSET`.
  - The `loading` map only prevents duplicate loads within one server.
  - Unload uses an unconditional `HDEL`, which can wipe another server's valid mapping.
  - This race remains even after B-C1 is fixed.
- **Impact:** two members rejoin at the same moment on S1 and S2, and both servers load the island. On unload both save, and one member's progress is lost.
- **Fix:** claim the island atomically before loading (`HSETNX`, or `SET NX PX` as a lease refreshed by the host). Only the winner loads. Release with a Lua compare-and-delete.

### B-H3. Host mappings never expire, so islands on a crashed server stay unreachable
- **Where:** `service/ServerService.java:46-62`, `service/IslandService.java:130-135`, `service/WorldService.java:123-178`.
- **Problem:**
  - `skyblock:islands:server` has no TTL and is never checked against the live-server heartbeat.
  - Server UUIDs stay the same across restarts, and nothing clears a server's own entries on startup.
- **Impact:** after a crash, every island that server hosted routes to a server that is down. After it restarts, they route to a server that hasn't loaded them. They stay unreachable until someone edits Redis by hand.
- **Fix:**
  - On startup, delete every mapping that points to this server.
  - In `resolveLocation`, check the host against `skyblock:servers:*`; if it isn't live, compare-and-delete the mapping and re-place the island.
  - Best: use a lease (see B-H2).

### B-H4. `/is create` loads the new world on whatever server the player is on
- **Where:** `service/IslandService.java:201` → `service/WorldService.java:180-203`. Called from `command/SkyblockCommand.java:61-64` and `action/island/create/CreateIslandAction.java:32`.
- **Problem:** creation never checks `isIslandServer()` or `maximumIslands`, and never goes through placement.
- **Impact:** islands created from a hub live in the hub with no protection, no idle unload and no border. Host routing then points other players at the hub too.
- **Fix:** persist the island row, then place it through the same path as `/is go` (an RPC with a "create from blueprint" flag). At minimum, refuse creation when `!isIslandServer()`.

### B-H5. Island settings changes never reach other servers
- **Where:**
  - `repository/IslandRepository.java:276-301` (`updateSettings`)
  - `service/IslandService.java:568-600`
  - `utils/ASConstants.java:19,35`: `FLAG_CACHE_KEY` and `FLAG_UPDATE_CHANNEL` are unused, left over from the planned FlagRepository (plan Task 9).
- **Problem:** the change is written to the DB and to the local in-memory island only. No packet, no invalidation and no refresh follows.
- **Impact:** the owner turns PVP, TNT or fire spread off from the hub, and the host server keeps enforcing the old values, including the time and weather locks, until some unrelated cascade or a restart.
- **Fix:** after the write, publish a settings refresh. Receivers re-read `island_flags` and run `applyEnvironment` on the host's main thread.

### B-H6. Whole-row saves from stale island objects revert other servers' changes
- **Where:**
  - `repository/UUIDSyncedRepository.java:19-22,44-46`
  - `service/LevelService.java:306-313`
  - `service/IslandService.java:347,392,428`
- **Problem:**
  - Every `save(island)` upserts every column.
  - A remote refresh swaps in a *new* `Island` instance, but the level scan holds the old one for seconds.
- **Impact:**
  1. The host server starts a scan.
  2. The owner runs `/is rename` or `/is close` from the hub.
  3. The scan finishes and saves the old name and the old `locked=false`, then publishes them.
  4. The change is reverted everywhere. The player was told the island is "closed", but visitors can still enter.
- **Fix:**
  - Use targeted UPDATEs per operation, e.g. `UPDATE islands SET level=?, value=? WHERE id=?`. `CrudRepository.update(column map)` already exists.
  - Get the island from the cache again right before changing it.
  - Optionally add a `version` column for optimistic locking.

### B-H7. The island name index isn't updated when islands load or refresh
- **Where:** `repository/IslandRepository.java:52-81`, `:473-483`, `:502`.
- **Problem:** `nameIslandMap` is only written in `cacheLocally` (warmup and local save). Values that arrive through the cache loader, i.e. remote refreshes and lazy loads, never update it.
- **Impact:**
  - A rename on server A leaves the old name resolvable and "taken" on every other server, and the new name can't be found there.
  - Islands created after startup can only be found by name on the server that created them.
- **Fix:** wrap the loader in `buildCache`, as `IndexedSyncedRepository` does, so every value loaded into L1 updates the index and retires the old name.

### B-H8. The spawner-rate multiplier compounds once per spawned mob
- **Where:** `listener/UpgradeEffectsListener.java:218-239`.
- **Problem:** `SpawnerSpawnEvent` fires once per entity, about 4 per cycle. Each event schedules its own `delay /= multiplier`.
- **Impact:** a ×2 upgrade produces about ×16 spawn speed. Even level 1 (×1.25) gives about ×2.4.
- **Fix:** apply the division once per spawner per tick, e.g. track a `Set<Block>` cleared each tick. Or set min and max spawn delay once when the upgrade changes or the spawner is placed.

### B-H9. `/is sethome` moves the world border's centre, so the border can be walked outward
- **Where:**
  - `service/UpgradeService.java:451`: `setCenter(island.spawnX(), island.spawnZ())`
  - `service/IslandService.java:372-401`: `setHome` has no bounds check
  - `service/LevelService.java:296-297`
  - `service/BiomeService.java:172-173`
- **Problem:** the border, the level-scan box and the biome-repaint box are all centred on the island home, and members can move the home.
- **Impact:**
  1. A member sets home at the edge of the border.
  2. After the next world reload, the border re-centres on that home.
  3. Repeating this walks the border outward without limit.
  4. Old builds then fall outside the level scan (level drops) and outside the hopper count (the cap undercounts).
- **Fix:** store a fixed island centre at creation and centre the border and both scans on it. Have `setHome` reject locations outside the border.

### B-H10. Entities aren't protected, and placing entities or priming TNT isn't checked
- **Where:**
  - `listener/IslandSettingsListener.java:57-63`: the only damage handler, and it covers PvP only
  - `listener/IslandPermissionsListener.java:132-146`
  - `IslandSettingsListener.java:97-111`: no case for `END_CRYSTAL`, and no `BlockExplodeEvent` handler
- **Problem:** none of these are checked:
  - `EntityDamageByEntityEvent` against animals, villagers or golems;
  - `HangingBreakByEntityEvent`;
  - `PlayerArmorStandManipulateEvent`;
  - `VehicleDestroyEvent`;
  - removing or rotating items in item frames;
  - `EntityPlaceEvent`, `HangingPlaceEvent` and `TNTPrimeEvent`;
  - end-crystal and respawn-anchor explosions, which no setting covers.
- **Impact:** visitors can:
  - kill trading halls and breeding pens;
  - strip item frames and armor stands;
  - break chest minecarts;
  - prime the owner's TNT;
  - place an end crystal and blow up the base even with every explosion setting off.
- **Fix:** add entity permissions (`ENTITY_DAMAGE`, `ITEM_FRAME`, `ARMOR_STAND`, or map them to BREAK) and the handlers above. Gate entity placement on BUILD. Map `END_CRYSTAL` and `BlockExplodeEvent` to an explosion setting.

### B-H11. Many block-changing actions bypass BUILD and BREAK
- **Where:** `listener/IslandPermissionsListener.java`. `utils/SkyblockTags.HARVESTABLE` is unused.
- **Unchecked actions:**
  - changing a spawner with a spawn egg (`PlayerSetSpawnerTypeWithEggEvent`), which changes the spawner type and its value;
  - axe stripping, hoe tilling, shovel paths, copper waxing and scraping;
  - editing, dyeing or glowing signs;
  - taking lectern books;
  - chiseled bookshelves, flower pots, cake, composters and campfires;
  - farmland trampling (`Action.PHYSICAL`);
  - berry harvesting (`PlayerHarvestBlockEvent`).
- **Impact:** a visitor can trample farms, rewrite shop signs, or turn a blaze spawner into a pig spawner.
- **Fix:** add handlers mapped to BUILD or BREAK, or new permissions. Use the `HARVESTABLE` tag.

### B-H12. Member and coop caps can be exceeded by concurrent joins
- **Where:** `service/MemberService.java:83-93`, `service/CoopService.java:63-67`, `service/IslandFullException.java`.
- **Problem:** the Javadoc says the cap is enforced at write time, but the code checks the same cached `members().size()` the caller already checked. `MemberRepository.count()` exists but is never used.
- **Impact:** two players on two servers accept invites to a 4/5 island at the same moment, and the island ends up at 6/5.
- **Fix:** in one transaction, lock the island row with `SELECT … FOR UPDATE`, run `COUNT(*)`, then INSERT, and throw `IslandFullException` if full.

### B-H13. Accepting an invite doesn't re-check the inviter or the recipient
- **Where:** `service/InvitationService.java:122-190`, `command/InvitationCommand.java:62-94`.
- **Problem:** on accept, nothing checks that:
  - the sender is still a member with `INVITE_MEMBER` or `COOP_MEMBER`;
  - the recipient is not already on another island (`uq_member_player` turns this into an UNEXPECTED_ERROR, and the invite row is kept);
  - for a coop invite, the recipient hasn't become a member or coop in the meantime.
- **Impact:** a member who was kicked for abuse still has valid invites that let friends in for the full TTL.
- **Fix:** re-resolve the sender and check their permission. Check `findPlayerIsland`, with a clear "leave your island first" message.

### B-H14. Kick, leave and uncoop don't move the player off the island
- **Where:** `service/MemberService.java:131-217`, `service/CoopService.java`. Nothing listens to `IslandMemberLeaveEvent` or `IslandCoopRemoveEvent`.
- **Impact:** a kicked member stays on a closed island with VISITOR access until they log out.
- **Fix:** call `bans().evict(islandId, target)` after a kick or uncoop, at least when the island is locked.

### B-H15. Unsaved setting and permission toggles take effect immediately and are never rolled back
- **Where:**
  - `model/island/Island.java:223-247`: `isSettingEnabled` reads `dirtySettings`
  - `model/role/IslandRole.java:54-76`
  - `action/island/settings/ToggleIslandSettingAction.java:29-37`
  - `action/island/role/ToggleRolePermissionAction.java:33-35`
- **Problem:**
  - Each click changes the **shared cached** object on this server at once, and the world environment is applied immediately.
  - Changes are applied to memory *before* the DB write and aren't reverted if the write fails.
  - If the flush is refused (the editor was demoted mid-session), the pending change stays live on this server indefinitely.
  - Two editors share one pending map.
- **Impact:** an officer toggles visitor BUILD and closes the menu without saving. Visitors can build on this server only, the DB disagrees, and the change silently reverts after a restart.
- **Fix:** keep pending edits per viewer, in the menu session. Enforcement reads only committed state. Apply to memory only after the DB write succeeds.

### B-H16. Startup warmup is all-or-nothing, and finding a player's island depends on it
- **Where:** `repository/IslandRepository.java:410-426`, `service/IslandService.java:48-52`, `repository/MemberRepository.java:57-60`.
- **Problem:**
  - One failed cascade fails its whole page and stops all later pages. Causes include B-C2, an unknown flag (B-M11), or a timeout.
  - `findPlayerIsland` reads only the in-memory map, and nothing loads a player's island on join (`MemberRepository.findByPlayer` exists but is unused).
- **Impact:** one bad row leads to a large share of players getting "you don't have an island" until the row is fixed and the server restarted.
- **Fix:**
  - Make per-island failures non-fatal (log and skip).
  - Load the player's membership on join when it's missing from the cache.
  - Bound warmup concurrency.

### B-H17. The coops menu is always empty
- **Where:** `model/island/Island.java:260-301`. The `get()` switch has `bans` but no `"coops"` case. `resources/menus/coops.yml:9` uses `%parameters_island_coops%`.
- **Fix:** one line: `case "coops" -> ItemProvider.of(coops());`

### B-H18. `block-values.yml` uses pre-1.13 material names, so most entries are ignored
- **Where:** `resources/block-values.yml` (233 keys), `configuration/BlockValueConfiguration.java:41,52-65`.
- **Problem:**
  - Names such as `LOG`, `WOOL`, `CONCRETE`, `STAINED_CLAY`, `BED_BLOCK`, `WORKBENCH`, `SMOOTH_BRICK` and `SIGN_POST` don't match any 1.21 `Material`, and are silently skipped.
  - Modern blocks (netherite, deepslate, copper, every newer wood type) are missing.
  - There are no spawner entries.
  - Entity keys can never score, because the scan reads blocks only.
- **Impact:** a wood or netherite base scores about 0, while cobblestone spam climbs `/is top`.
- **Fix:** regenerate the file with modern names (or tags), log unmatched keys at load, and add spawner entries.

### B-H19. `/is go` ignores bans and closed islands, and fails silently when no server is available
- **Where:** `command/SkyblockCommand.java:75-93`, `service/IslandService.java:114-171`.
- **Problem:**
  - `WarpService.teleport` checks bans and the lock *before* `resolveLocation`; `onGo` doesn't. Each call can load a world and transfer the player across servers, only for them to be bounced on arrival.
  - A `null` location (no server can host) is passed straight to `teleport`.
  - An `orElseThrow` inside `whenComplete` throws into a future nobody reads, so the player gets no message.
- **Fix:** reuse the warp pre-checks, handle a `null` result with a message, and add `.exceptionally`.

## Medium

**Cross-server, messaging and cache coherence**

- **B-M1. Packet handlers run in parallel, so the order of packets is lost.**
  - **Where:** AstralCore `MessagingService.java:44-47,340-341` uses a virtual-thread-per-task executor.
  - **Impact:** CoopAdd and CoopRemove (or Update and Delete) can apply in reverse order, leaving phantom coops or resurrected islands in remote caches.
  - **Fix:** use one serial executor per exchange, or version the packets.
- **B-M2. Relationship refreshes aren't merged or ordered.**
  - **Where:** `repository/IslandRepository.java:109-143,333-339,383-403`.
  - **Problem:** the refresh that finishes last wins, even if it read the database first. `populate` sets about 8 non-volatile fields one at a time while the main thread reads them.
  - **Impact:** a kicked member can reappear in a server's cache.
  - **Fix:** allow one refresh in flight per island, followed by at most one re-run. Publish an immutable snapshot with a single volatile write.
- **B-M3. Island delete removes slime storage without confirmation from the remote host.**
  - **Where:** `service/WorldService.java:447-483`.
  - **Problem:** queues are non-durable, so a host that is reconnecting misses the packet and later saves the world again.
  - **Impact:** an orphaned world, or a deleted island that is still live.
  - **Fix:** wait for an acknowledgement from the host, and add a persistent tombstone that the load and save paths check.
- **B-M4. Delete publishes the invalidation before the Redis DEL completes.**
  - **Where:** `repository/SyncedRepository.java:76-93`.
  - **Impact:** another server can re-cache the deleted island from L2. Islands have no L2 TTL.
  - **Fix:** chain the publish after the DEL, and set a TTL.
- **B-M5. A failed L2 write is swallowed, but the update is still published.**
  - **Where:** `repository/SyncedRepository.java:63-74,176-186`.
  - **Impact:** other servers refresh from the stale L2 copy. Concurrent saves can leave L2 and the DB permanently different.
  - **Fix:** on failure, DEL the key and publish an invalidation instead.
- **B-M6. Placement uses stale load counts and keeps routing to stopped servers.**
  - **Where:** `service/ServerService.java:22-44`, `AstralSkyblock.java:226-248`.
  - **Problem:**
    - The heartbeat is sent every 15 s with a 60 s TTL, and is never deleted in `onDisable`.
    - The async heartbeat can report `loadedIslands=0` mid-shutdown, making the stopping server look emptiest.
    - Nothing enforces `maximumIslands` on the receiving server.
  - **Fix:**
    - Delete the heartbeat at the start of `onDisable`.
    - Reject loads when at the cap.
    - Refresh the heartbeat after each load or unload.
    - Drain in-flight DB futures before `database.disconnect()`.
- **B-M7. Idle unload doesn't re-check its conditions when it runs, and ignores players in transit.**
  - **Where:** `service/WorldService.java:574-597,380-434`.
  - **Impact:** a visitor being transferred in lands on an unloaded world, or is evacuated straight to the hub.
  - **Fix:** re-check `world.getPlayers().isEmpty()` inside the task, and add a "pending arrival" marker.
- **B-M8. Packet handlers dereference `plugin.islands()` before it is assigned.**
  - **Where:** `repository/MemberRepository.java:37-49`, `RoleRepository.java:52-67`, `UpgradeRepository.java:38-51`.
  - **Problem:** these are registered before `IslandService` is constructed, and that construction blocks during warmup.
  - **Impact:** NPEs drop refreshes during boot, leaving stale state.
  - **Fix:** construct `IslandService` first, or add null guards and buffer packets until enable finishes.
- **B-M9. Check-then-act races on the per-player ban and coop index sets.**
  - **Where:** `repository/BanRepository.java:199-208,222`, `CoopRepository.java:197-203`.
  - **Impact:** a ban entry can be lost during warmup, so the banned player can be re-invited or use warps.
  - **Fix:** do it atomically with `computeIfPresent` / `compute`.
- **B-M10. FKs to `players` break bans, coops and invites for players who never joined a skyblock server.**
  - **Where:** `resources/schema.sql:188,212,233,313-314`, `service/PlayerService.java:18-24`.
  - **Problem:** this also races the async `recordSeen` upsert on join.
  - **Fix:** upsert the `players` row inside the same transaction, or drop these FKs.
- **B-M11. `IslandSettings.valueOf` throws on an unknown flag, and new settings default to false.**
  - **Where:** `repository/IslandRepository.java:319,177-180`.
  - **Impact:** renaming an enum constant breaks every island cascade (see B-H16). Settings are not "override-only" as the schema documents.
  - **Fix:** parse defensively and fall back to the configured default when a row is missing.
- **B-M12. No schema migrations, and the schema runner aborts on the first failing statement.**
  - **Where:** `resources/schema.sql`, commit `d1367a7`, AstralCore `DatabaseService.runSchema`.
  - **Problem:**
    - Existing databases never receive `islands.value` or `island_warps.icon/display_name/description`.
    - Comment-only chunks can make the first statement fail ("Query was empty"; verify on your MariaDB), and then no tables are created at all.
  - **Fix:** use Flyway, or `ALTER … ADD COLUMN IF NOT EXISTS`. Strip comments before running, and catch errors per statement.

**Island and social logic**

- **B-M13. Island creation has several gaps.**
  - **Where:** `service/IslandService.java:173-249`, `command/SkyblockCommand.java:58-65`, `action/island/create/CreateIslandAction.java`.
  - **Problems:**
    - It never checks for an existing island, so the player gets UNEXPECTED_ERROR after a DB round trip.
    - Names are not sanitised (unlike `rename`), which allows MiniMessage injection on `/is top`.
    - There is no length check, so names over 64 characters fail at the DB.
    - When the name is blank, it falls back to the player's name. If an island called "Steve" exists, Steve can never create one from the menu.
  - **Fix:**
    - Check membership first.
    - Use the same `PlayerText.sanitise` and length limit as `rename`.
    - On a fallback collision, leave the island unnamed or add a suffix.
- **B-M14. The kick rank check is skipped when the kicker is not a member.**
  - **Where:** `service/MemberService.java:150-157`.
  - **Impact:** a coop or visitor role that was given `KICK_MEMBER` (via B-H1) can kick the island's Admin.
  - **Fix:** mirror `canBanMember`, which refuses this case.
- **B-M15. The coop snapshot list can hold duplicates.**
  - **Where:** `service/CoopService.java:69-70,170-177`.
  - **Problem:** this happens on a double accept or a packet re-add, and the cap then counts the player twice.
  - **Fix:** `removeIf(same player)` before `add`.
- **B-M16. Invitations can be duplicated, and the pending check ignores the invitation type.**
  - **Where:** `service/InvitationService.java:83-97`, `schema.sql:301-315`.
  - **Problem:**
    - Two invites at the same moment give `MULTIPLE_PENDING_INVITATIONS`.
    - A pending COOP invite blocks a MEMBER invite.
  - **Fix:** add `UNIQUE (island_id, recipient_id, type)` and filter `findPending` by type.
- **B-M17. Member and role writes never check affected rows.**
  - **Where:** `repository/MemberRepository.java:128-157`, `RoleRepository` rename and `setWeight`.
  - **Impact:** "kicked" or "promoted" is reported and events fire even when nothing changed.
  - **Fix:** return the row count and treat 0 as not found.
- **B-M18. Partial failures leave the UI and the DB out of step.**
  - **Where:** `service/RoleService.java:192-194` (rename and weight run as 2 non-transactional writes), `InvitationService.java:159`.
  - **Problem:** if the invite delete fails after the add succeeded, the player has joined but is told it failed.
  - **Fix:** run rename and weight in one UPDATE (`RoleRepository.update(IslandRole)`). In accept, treat a failed invite delete as a warning, not a failure.
- **B-M19. Members are not notified when their island is deleted, and indexes keep stale entries.**
  - **Where:** `service/IslandService.java:271-306`.
  - **Fix:** notify members via `ChatService`, and call `evictIndex(islandId)` on every relationship repository.

**Gameplay**

- **B-M20. The level scan's chunk cap is smaller than the upgraded borders.**
  - **Where:** `service/LevelService.java:38,294`, `BiomeService.java:42,170`.
  - **Problem:** `MAX_CHUNK_RADIUS = 64` covers 1024 blocks, but `worldborder.yml` levels 2-4 reach a radius of 1500-2500.
  - **Impact:** blocks beyond 1024 aren't scored, hoppers aren't counted, and the biome isn't painted there.
- **B-M21. The hopper cap can be bypassed.**
  - **Where:** `service/BlockLimitService.java:32-50`, `listener/UpgradeEffectsListener.java:64-99`, `listener/IslandListener.java:39-45`, `service/LevelService.java:132`.
  - **Problem:**
    - The count reads 0 until the first scan after a load finishes.
    - `seed()` overwrites hoppers placed during the scan.
  - **Fix:**
    - Refuse placement until the island has been counted.
    - Record placements and breaks made during a scan and add them to the result.
    - Or count hopper tile entities directly.
- **B-M22. Every block with a configured value counts as "valuable".**
  - **Where:** `listener/IslandPermissionsListener.java:60-65,205-211`.
  - **Impact:** `COBBLESTONE: 1` and `DIRT: 2` require `VALUABLE_BREAK`, so coops can't mine the island's generator.
  - **Fix:** add a value threshold, or an explicit list of valuable blocks.
- **B-M23. Paid generator upgrade has no effect, and the generator lookup has no fallback.**
  - **Where:** `resources/upgrades/generator.yml`: levels 0 and 1 both use `overworld`.
  - **Where:** `service/UpgradeService.java:407-419`: an exact `get(level)`, unlike the "highest configured level at or below" lookup in `value()`.
  - **Impact:** players pay 10,000 for nothing.
- **B-M24. Warps have three issues.**
  - **Where:** `service/WarpService.java:79-101,185-242,285-319`, `repository/WarpRepository.java:121-133`.
  - **Problems:**
    - No safety or border check, so a warp set while flying drops visitors into the void.
    - Edits change the cached warp before the write and aren't rolled back on failure, so privacy can differ between servers.
    - The limit check races, so two quick `setwarp`s exceed it, and `ON DUPLICATE KEY UPDATE` silently overwrites a warp with the same name.
  - **Fix:**
    - Validate the location on create and on teleport.
    - Change a copy, then persist it.
    - Use a conditional INSERT for the limit and a plain INSERT for create.

**Protection gaps**

- **B-M25. `DROP_ITEMS` is checked on `EntityDropItemEvent`.**
  - **Where:** `listener/IslandPermissionsListener.java:92-96`.
  - **Problem:** players dropping items fire `PlayerDropItemEvent`, so the check never runs.
- **B-M26. `DYE_SHEEP` is checked on `PlayerInteractAtEntityEvent`.**
  - **Where:** `IslandPermissionsListener.java:122-130`.
  - **Problem:** the dye is applied on the second packet, so it still works.
  - **Fix:** use `SheepDyeWoolEvent`.
- **B-M27. `CROPS_GROWTH` doesn't stop sugar cane, cactus, melons or pumpkins.**
  - **Where:** `listener/IslandSettingsListener.java:51-55`.
  - **Problem:** it tests the air block where the plant grows into.
  - **Fix:** test `getNewState().getType()`.
- **B-M28. `FLY` only blocks starting to fly.**
  - **Where:** `IslandPermissionsListener.java:173-180`.
  - **Problem:** players already flying when they arrive keep flying.
  - **Fix:** check again on world change or teleport, and when permissions change.
- **B-M29. Bans and closed islands can be bypassed by respawning.**
  - **Where:** `listener/IslandListener.java:61-80`.
  - **Problem:** a bed or respawn anchor on the island puts the banned player back inside.
  - **Fix:** handle `PlayerRespawnEvent`.
- **B-M30. `EGG_LAY` checks only `Material.EGG`.**
  - **Where:** `IslandSettingsListener.java:84-89`.
  - **Problem:** it misses `BROWN_EGG` and `BLUE_EGG`.

**UI and commands**

- **B-M31. Menus check the viewer's own island, not the island being shown.**
  - **Where:** `placeholder/SkyblockPlaceholders.java:23-33`, `menus/main.yml`, `members.yml`, `member-manage.yml`, `warps.yml`, `upgrades.yml`.
  - **Problem:** a player can reach another island's menus via top → warps → back, or `/is warps <x>`. There they can browse its members, roles, permissions and settings, and see buttons that won't work.
  - **Fix:** add an island-scoped `%parameters_island_hasPermission_X%`.
- **B-M32. Role dialogs: names with spaces break parsing, and editing resets the weight.**
  - **Where:** `resources/dialogs/role-create.yml:29`, `role-edit.yml:17-28`.
  - **Fix:** put the name last in the action parameters, and give the slider an `initial` value.
- **B-M33. Island-name arguments are case-sensitive and can't contain spaces.**
  - **Where:** `command/context/IslandContextResolver.java:18-30`, `IslandRepository.java:35,473-478`.
  - **Problem:** the DB treats names as case-insensitive. `/is go steve` fails for "Steve", and "Sky Land" can't be targeted.
  - **Fix:** use lower-case keys, and forbid spaces in names or join the remaining arguments.
- **B-M34. Fire-and-forget futures have no error handler.**
  - **Where:** `service/InvitationService.java:83-113,132-189,37-38`, `command/InvitationCommand.java:66-125`, packet-handler chains, and every command or action caller.
  - **Impact:** DB errors show the player nothing and leave no log line.
  - **Fix:** add a `Futures.logged(...)` helper or a terminal `.exceptionally`.

## Low

| ID | Where | Issue |
|---|---|---|
| B-L1 | `service/BanService.java:106-124,207-225`; `IslandService.java:395` | Bukkit player and world are accessed from DB or messaging threads (the evict handler, sethome). |
| B-L2 | `service/WorldService.java:213-247` | The load-coalescing check-then-act can run a second `asp.loadWorld`, which fails spuriously. |
| B-L3 | `service/IslandService.java:161` | The RPC timeout is the default 5 s, while a cold load can take longer (the outer timeout is 15 s). |
| B-L4 | `service/CoopService.java:170-177` | `CoopAddPacket` can duplicate an entry that is already in a refreshed snapshot. |
| B-L5 | `service/MemberService.java:240,300` | Promote and demote reject non-member `skyblock.admin`, but `setRole` allows it. |
| B-L6 | `service/MemberService.java:418`; `command/MemberCommand.java:92-111` | Transferring to yourself reports success. |
| B-L7 | `service/InvitationService.java:132-159` | A double `/is accept` either errors or, for coops, fires the event twice. Claim the invite atomically first. |
| B-L8 | `service/InvitationService.java:241-247` | Only the original sender can cancel an invite; invites from kicked members stay usable. |
| B-L9 | `service/MemberService.java:55-63` | If the island is evicted from L1, `findPlayerIsland` returns NO_ISLAND because there is no async fallback. |
| B-L10 | `service/IslandService.java:202-237` | `create` uses a `Player` that may have logged out during the async steps. |
| B-L11 | `service/RoleService.java:124-157` | There is no cap on roles per island, and they can't be deleted (U-H2). |
| B-L12 | `listener/UpgradeEffectsListener.java:162-165` | The minecart cap allows limit+1 (the event fires before the entity is added). Only loaded chunks are counted. |
| B-L13 | `configuration/GeneratorConfiguration.java:25-35` | A weight ≤ 0 leaves a partly built collection cached; empty `blocks` causes an NPE on every form event. |
| B-L14 | `listener/GeneratorListener.java:59-62` | Generated leaves are non-persistent and decay, sand floats, and basalt is replaced by the overworld table. |
| B-L15 | `repository/IslandRepository.java:434-438` | The leaderboard ranks by the coarse `level` and breaks ties by `updated_at`, which is frozen at creation (upsert binds it). Use `value DESC, id`. |
| B-L16 | `service/BiomeService.java:56-62` | No biome whitelist: nether biomes enable ghast or piglin farms. No cost or cooldown. |
| B-L17 | `service/UpgradeService.java:249-258` | A `runTask` throwing during disable refunds an already-committed upgrade. |
| B-L18 | `resources/upgrades/*.yml`; `UpgradeService.java:196` | `currency: "default"` is treated as a named currency id. Purchases fail unless that id exists *(verify against EconomyService)*. |
| B-L19 | `listener/IslandSettingsListener.java:38-49` | `WATER_FLOW` ignores flow from waterlogged blocks. |
| B-L20 | `IslandSettingsListener.java:65-77` | `FIRE_SPREAD` doesn't cover lava- or lightning-started fire (no `BlockIgniteEvent`). |
| B-L21 | `IslandSettingsListener.java:57-63` | PvP can be bypassed with tamed wolves and harmful splash or lingering potions. |
| B-L22 | `IslandSettingsListener.java:103-106` | A reflected ghast fireball bypasses `GHAST_FIREBALL`. |
| B-L23 | `IslandPermissionsListener.java:217-222`; `IslandSettingsListener.java:190-195` | If the island is missing from the local cache while its world is loaded, every check returns "allowed". Deny instead. |
| B-L24 | `IslandPermissionsListener.java:61-62,143-145` | `SPAWNER_BREAK` misses trial spawners and vaults; `SADDLE_ENTITY` misses harnesses. |
| B-L25 | `listener/PlayerConnectionListener.java:57-58` | `restore()` calls `orElseThrow` on TeleportationService, and silently does nothing if the island is not cached within 40 ticks. |
| B-L26 | `command/context/IslandContextResolver.java:22-25` | A console sender running `/is upgrade set` without an island causes an NPE. |
| B-L27 | `model/member/IslandCoop.java:42` | A null `addedBy` is passed to `MinecraftPlayerPlaceholder` in the coops menu. |
| B-L28 | `resources/menus/confirm/confirm-kick.yml`, `confirm-ban.yml`, `member-role.yml` | The list reopens before the async write finishes, so it shows stale data. |
| B-L29 | `resources/menus/members.yml:18`, `member-manage.yml:13`, `member-role.yml:9` | The owner's role renders as null; VISITOR and COOP are listed as assignable but always rejected. |
| B-L30 | `repository/WarpRepository.java:121-134`; `model/island/Island.java:63-79` | The warp upsert doesn't update the name's case. `Island` relationship fields are non-volatile, mutable collections shared across threads. |

---

# 3. Performance

## High

### P-H1. Every island save triggers an 8-query cascade on every server
- **Where:**
  - `repository/SyncedRepository.java:63-74`
  - `UUIDSyncedRepository.java:19-22`: refresh even when the island isn't cached locally
  - `IslandRepository.java:157-159`: `postLoad` runs a full cascade
  - `MemberRepository.java:37-49`, `RoleRepository.java:52-67`
  - `LevelService.java:306-313`: persists even when nothing changed
- **Impact:** 2,000 hosted islands × 5 remote servers × 8 queries = **80,000 queries per rescan pass**. This exhausts the default 10-connection Hikari pool, and unrelated queries hit the 30 s timeout. Toggling 10 permissions sends 10 invalidations, each costing S × 7 queries.
- **Fix:**
  - Skip the save when `value` and `level` haven't changed.
  - Make update packets carry what changed, and use the targeted refreshes that already exist (`refreshUpgrades`, `refreshBans`, `refreshWarps`).
  - Only refresh keys already present in L1.
  - Debounce changes per island (100-250 ms).

### P-H2. Startup warmup blocks `onEnable` and loads the whole network into every server
- **Where:** `service/IslandService.java:49-53` (`warmup().join()` on the main thread), `repository/IslandRepository.java:410-426`, `utils/ASConstants.java:24` (pages of 500).
- **Impact:**
  - Every server, lobbies included, cascades every island at 7 sequential queries each, with 500 islands in flight per page. With 50k islands that is about 350k queries per boot, so boot takes minutes and the watchdog fires.
  - Every server holds the whole network's member, role, ban and warp graph in memory.
- **Fix:**
  - Warm up in the background and serve misses through the existing async loader.
  - Bound concurrency to the pool size.
  - Batch the relation queries per page (`WHERE island_id IN (...)`).
  - Only warm islands this server needs: hosted ones, and online players' islands.

### P-H3. `BanRepository.onPrimed` walks every banned player on each prime, so warmup is quadratic
- **Where:** `repository/BanRepository.java:215-224`.
- **Impact:** with 50k islands and 50k bans, warmup does about 2.5 billion set operations. At runtime, every member or role change walks the whole map on every server.
- **Fix:** keep a reverse index `islandId → Set<player>` and only compare that island's previous entries with its new ones.

### P-H4. Synchronous slime-world saves on the main thread
- **Where:** `service/WorldService.java:180-191` (create), `:404` (idle unload), `:142-147` (shutdown, one world at a time).
- **Problem:** `asp.saveWorld` runs inside a `thenApply` that runs on the main thread. On create the save is also **redundant**, because `clone(name, loader)` has already persisted the world.
- **Impact:** a TPS drop on every `/is create`. Shutting down with 100 islands can exceed the stop timeout, and the remaining worlds aren't saved.
- **Fix:**
  - Drop the save on create.
  - For idle unload, take the snapshot on the main thread and write it asynchronously.
  - At shutdown, save in parallel on a bounded executor with a timeout.

## Medium

- **P-M1. Redis `KEYS` on every island placement.**
  - **Where:** `service/ServerService.java:38-44` → `CacheRepository.findByPattern`.
  - **Problem:** each cold `/is home`, warp, visit or restore scans the whole keyspace, which also holds all island L2 entries.
  - **Fix:** keep the server list in one sorted set or hash, or cache it locally for a few seconds.
- **P-M2. Unbounded caches, and index entries that are never evicted.**
  - **Where:** `repository/IndexedSyncedRepository.java:44-58,117-120` (`evictIndex` is never called), `SyncedRepository.java:49-53`, `PlayerRepository.java:45-62`, `LevelService.java:52` (`lastScan`).
  - **Problem:**
    - Every player who ever joins is cached forever.
    - A deleted island's members, roles, coops, bans, warps and upgrades stay on every server.
    - There is no `PlayerQuitEvent` handler.
  - **Fix:**
    - Give the player cache `expireAfterAccess` and `maximumSize`.
    - Call `evictIndex` on delete.
    - Prune `lastScan` on unload.
- **P-M3. Every `BlockBreakEvent` runs about 230 string comparisons, in every world.**
  - **Where:** `listener/IslandPermissionsListener.java:57-66,205-211`, `configuration/BlockValueConfiguration.java:27-45`.
  - **Problem:** this is the hottest event in skyblock (cobble generators). The check also runs before the island lookup.
  - **Fix:** look up the island first, then use `materialValues().containsKey(type)`, which is an O(1) EnumMap lookup.
- **P-M4. Periodic rescans walk every hosted island every 900 s, whether or not anything changed.**
  - **Where:** `service/LevelService.java:291-303,325-384`.
  - **Problem:** each scan makes up to about 16.6k `isChunkGenerated` calls, takes snapshots and saves.
  - **Fix:** mark islands dirty on block events, skip clean ones, and skip the save when nothing changed.
- **P-M5. `/is calc <island>` lets anyone scan any island.**
  - **Where:** `command/LevelCommand.java:28-48`.
  - **Problem:** the cooldown is per island, so a player can cycle through every hosted island.
  - **Fix:** restrict it to members or admins, add a per-player cooldown, and round the displayed cooldown up.
- **P-M6. `/is biome` loads unloaded chunks synchronously.**
  - **Where:** `service/BiomeService.java:133-150`.
  - **Problem:** `setBiome` on an unloaded chunk loads it on the main thread, and there is no cooldown.
  - **Fix:** load chunks with `getChunkAtAsync` first.

## Low

| ID | Where | Issue |
|---|---|---|
| P-L1 | `command/completion/IslandCompletionHandler.java:19` | `@islands` completion returns every island name on the network on each keystroke, including locked islands. Filter by prefix and cap the results. |
| P-L2 | `resources/schema.sql:101` | The leaderboard `ORDER BY level DESC, updated_at` causes a filesort. Index `(level, updated_at)`, or `value`. |
| P-L3 | `repository/PlayerRepository.java:45-62` | Every join broadcasts an invalidation and runs a SELECT whose result is never read. |
| P-L4 | `service/InvitationService.java:37-38,329-331` | Every server prunes invites every minute with a full scan (no `expires_at` index), and errors are discarded. |
| P-L5 | `service/WorldService.java:473-482`; `LevelService.java:169` | Blocking JDBC calls and chunk summing run on `ForkJoinPool.commonPool`. |
| P-L6 | `listener/IslandPermissionsListener.java:117` | `InventoryOpenEvent#getHolder()` builds a BlockState snapshot; use `getHolder(false)`. |
| P-L7 | `model/island/Island.java:114-156`; `SyncedRepository.java:50-52` | Each permission check runs up to 3 streams; `recordStats()` runs on every hot-path L1 hit. |
| P-L8 | `listener/GeneratorListener.java:51` → `UpgradeService.java:415` | A misconfigured generator logs a WARN on every cobblestone formation. |
| P-L9 | `resources/database.properties` | No `maximumPoolSize`, so Hikari defaults to 10 connections, too small for warmup and the P-H1 fan-out. |

---

# 4. Unfinished features

There are **no TODO or FIXME markers** in the Java code; every gap below was found by comparing configs, docs (`docs/superpowers/`) and git history against the code.

Several cross-checks came back clean:
- every menu and dialog action is registered;
- all 112 `ASMessages` keys exist and are used;
- every `config.yml` key is read;
- every DB table is read and written;
- all nine `UpgradeType`s have an effect.

## High

- **U-H1. Protection model is incomplete.**
  - **Gap:** the permission enum lacks containers, doors, redstone, buckets, entity damage, item frames, armor stands, trampling, signs, lecterns and more.
  - **Covered in:** B-C5, B-C6, B-H10 and B-H11.
  - **Needed:** add the permissions, their handlers, entries in `permissions.yml`, and grants in `roles.yml`.
- **U-H2. Role deletion and changing the default role exist only in the repository.**
  - **Where:** `repository/RoleRepository.java:143` (`setDefault`), `:169` (`delete`), `:193` (`countHolders`).
  - **Gap:** no service, action, command or menu calls them, yet `menus/roles.yml:21` implies non-default roles can be deleted.
  - **Needed:** `RoleService.delete` (reassign the role's holders first) and `setDefault`, with weight checks, plus actions, a confirm menu and messages.
- **U-H3. The default blueprint and its source world are not shipped.**
  - **Covered in:** B-C3.
  - **Gap:** `sourceWorlds/` is created empty, and the README doesn't explain how to provide one.
- **U-H4. `block-values.yml` needs regenerating for 1.21.**
  - **Covered in:** B-H18.

## Medium

- **U-M1. The GUI covers only part of member and coop management.**
  - **Where:** `AstralSkyblock.java:123-130`.
  - **Gap:** `invite-member`, `coop-player`, `promote-member`, `demote-member` and `transfer-ownership` are registered but used by no menu. `member-manage.yml` has only role, kick and ban, although the spec lists transfer. There is no invite or "add coop" button, and no pending-invitations view or `/is invites` command.
- **U-M2. Upgrade content is placeholder.**
  - The generator upgrade has no effect (B-M23).
  - `worldborder.yml` has `unlock-actions: "[message] gg"` on every level and `currency-display: "Default Currency"`.
  - `IslandUpgrade.icon` is never set, so `menus/upgrades.yml` hardcodes `EXPERIENCE_BOTTLE` and shows raw enum names such as `WORLDBORDER_SIZE`.
  - `config.yml` `world-border-size: 100` conflicts with `worldborder.yml` level 0 (`1000`).
- **U-M3. Generators.**
  - **Gap:** only `overworld.yml` exists, and it also replaces basalt, although `UpgradeType.GENERATOR` is documented as "Cobble/basalt generator tier". There are no tiered generators.
- **U-M4. Settings sync across servers.** The planned FlagRepository / FlagService (plan Task 9) was never built; see B-H5.
- **U-M5. No tests.**
  - **Gap:** there is no `src/test/` and no JUnit or Mockito dependency, although surefire is configured.
  - **Gap:** plan Task 1 and every per-task test step are unchecked.
- **U-M6. `SkyblockAPI` (commit `294682b`) is read-only and has gaps.**
  - No mutation methods.
  - No `findRole`, `isOwner`, `isIslandWorld` or `isLocked`.
  - `findIslandByName` only searches the cache, and its "case-sensitive" Javadoc contradicts the DB collation.
  - `isMember(UUID,UUID)` uses the cache-only island lookup and can return false while `findIslandByPlayer` succeeds.
  - Calling the API before enable causes an NPE.
  - There is no separate API artifact.
  - The API returns live, mutable `Island` objects; changes made through them bypass persistence and sync.
- **U-M7. Events.**
  - **Existing events:** the six existing events (`IslandCreate`, `IslandDeleted`, `MemberJoin`, `MemberLeave`, `CoopAdd`, `CoopRemove`) have **no listeners** and none can be cancelled. Coop events are not re-fired on remote servers, while member events are.
  - **Missing events:** transfer, which the spec requires, has no event. Neither do ban/unban, upgrade purchase, level change, warp, settings, role, rename or lock.
- **U-M8. Coops never expire.**
  - **Gap:** the schema comment calls coops "temporary access", but they last until removed by hand.
  - **Needed:** decide the intended lifecycle (clear when the last member goes offline, or a TTL).
- **U-M9. Schema migrations.**
  - **Where:** commit `d1367a7` removed `SchemaMigrations`, see B-M12.
  - **Needed:** ship a versioned migration, or at least `docs/migrations/*.sql`.
- **U-M10. Mixed languages.**
  - **Gap:** `messages.yml`, `settings.yml` and `permissions.yml` are English, while every menu and dialog is French.
  - **Gap:** some strings are hardcoded in English and not configurable: `UpgradeCommand.java:63-88`, `SkyblockCommand.java:155-160` and the context resolvers.

## Low

1. **Dead constants and tags from abandoned plans.**
   - `ASConstants`: `ISLAND_LOCK_KEY`, `PERMISSION_CACHE_KEY`, `FLAG_CACHE_KEY`, `PERMISSION_UPDATE_CHANNEL`, `FLAG_UPDATE_CHANNEL`.
   - `SkyblockTags`: `CONTAINERS`, `MECHANISMS`, `SPAWNERS` (which would not even match `Material.SPAWNER`) and `HARVESTABLE`.
   - `model/island/FlagKey.java`.
2. **Unused repository methods.**
   - `MemberRepository.findOwner`, `findByPlayer` and `count`.
   - `RoleRepository.findDefault`, `findSystemRole` and `getIslandRoleIds`.
   - `UpgradeRepository.findLevel` and `PlayerRepository.findByName`. The latter has no `ORDER BY last_seen`, despite its Javadoc.
   - `WorldService.save(UUID)`, which would also save a live world off the main thread.
3. **`roles.yml` `icon` is never read.** `menus/roles.yml` hardcodes `player_head`.
4. **The edit-role slider can't be pre-filled.** This is a known limitation, mentioned in commit `87cb6cc`.
5. **Stale docs.**
   - `docs/superpowers/specs/actions.md` still says 14 actions need implementing.
   - The menus spec has the wrong paths.
   - The `schema.sql` header says "no invites table", calls ids UUIDv7 (the code uses v4), and mentions a bank that was removed.
   - The `island_upgrades` comment is truncated.
   - Comments reference `IslandService#hydrate` and a PermissionRepository, which don't exist, and describe messaging as Redis pub/sub (it is RabbitMQ).
6. **`queries.sql` is shipped but never loaded.** It references columns that don't exist.
7. **`island_invitations` table.** It has no `ENGINE/CHARSET` and no indexes on `recipient_id` or `expires_at`. The model's `@Column` annotations are wrong.
8. **Island creation writes every default flag** (18 INSERTs) instead of storing only overrides.
9. **`config.yml` `default-settings` omits `CREEPER_EXPLOSION`** while TNT, wither and ghast explosions are on. Check whether this is intended.
10. **The GUI ban has no reason input**, and visitors can only be banned with `/is ban`.
11. **The main menu has no links** to top, lock, rename, biome, sethome or level calc.
12. **There is no visitor list and no island browser** beyond `/is top` → warps.
13. **Two classes are named `IslandUpgrade`** (the entity and the blueprint), so `UpgradeService.java:82` has to fully qualify one.
14. **`plugin.yml` declares no permissions.** `skyblock.admin` and `skyblock.reload` are undeclared. Admin tooling is limited to `/is upgrade set` and `/is reload`.
15. **No void-fall protection.**
16. **Denied actions give no feedback**, so blocks silently reappear.
17. **`README.md` is empty**, and `/skyblock reload` silently ignores changes to `loader.yml`.

---

# 5. Checked and found correct

- **Upgrade purchases:** a local guard against double clicks, plus a conditional `UPDATE … WHERE level=?` in a transaction, a refund for the losing request, and max-level and permission checks.
- **Owner protection:** the owner can't be kicked, demoted, banned or have their role changed. Members can't promote themselves or anyone else above their own rank.
- **Packet coverage:** all 19 packet types are both sent and handled. Self-echo filtering works.
- **Service-side permission checks:** every write re-checks the island permission, so buttons shown by mistake can't perform the write.
- **Placeholders:** they read only from memory and never hit the DB.
- **Level and biome scans:** they take chunk snapshots on the main thread, sum off it, release their latches on every failure path, and skip ungenerated chunks.
- **Idle sweep and evacuation:** the idle sweep, the delete marking and evacuation all run on the main thread.
- **Hopper tracking:** pistons can't move hoppers, and explosions and breaks decrement the count correctly.

---

# 6. Suggested plan

1. **Hotfix (before any deploy):**
   - SEC-1 credentials
   - B-C3 blueprint
   - B-C2 timestamps
   - B-C4 dupe
   - B-H8 spawner rate
   - B-H17 coops menu (one line)
   - B-C8 coop `onPrimed`
2. **Protection pass:** B-C5, B-C6, B-H10, B-H11 and B-M25–M30 (new permissions and handlers), with a test matrix of a visitor attempting each interaction.
3. **Permission model:**
   - B-H1 escalation and owner-only disband
   - B-H15 per-viewer pending edits
   - B-M14 kick rank check
4. **Cross-server correctness:**
   - B-C1 and B-H2/H3/H4 (claim hosting atomically, use leases, route creation through placement)
   - B-H5 settings sync
   - B-H6 targeted UPDATEs
   - B-H7 name index
   - B-M1/M2 ordering of packets and refreshes
5. **Data integrity:**
   - B-C7 and B-M17 row-count checks
   - B-C9 disband order
   - B-H12 caps enforced in SQL
   - B-M12 migrations
   - B-M10 FK to `players`
6. **Scale:**
   - P-H1 targeted refreshes and debouncing
   - P-H2 lazy or background warmup
   - P-H3 reverse ban index
   - P-H4 async world saves
   - P-M1 no `KEYS`
   - P-M2 bounded caches
7. **Content and features:**
   - regenerate `block-values.yml`
   - tiered generators
   - upgrade display names and icons
   - role deletion
   - GUI buttons for invite, coop, promote and transfer
   - events
   - tests
