# Coop & Members Feature Design

**Date:** 2026-06-30
**Status:** Approved

---

## Overview

Wire the island coop and member management features into AstralSkyblock. The domain models (`IslandMember`, `IslandCoop`, `IslandBan`) and all relevant `IslandPermission` values already exist. This spec covers everything that's missing: the invitation system, repositories, service-layer write operations, events, cross-server packets, commands, and GUI actions.

The design mirrors AstralTown's architecture (separate focused services, one per concern) for consistency across both plugins.

---

## Data Model

### New entity: `IslandInvitation`

Table: `island_invitations`

| Column | Type | Notes |
|---|---|---|
| `id` | `VARCHAR(36)` PK | `UUID uniqueId` |
| `island_id` | `VARCHAR(36)` | |
| `sender_id` | `VARCHAR(36)` | |
| `recipient_id` | `VARCHAR(36)` | |
| `type` | `ENUM('MEMBER','COOP')` | drives what `accept` does |
| `expires_at` | `BIGINT` | `createdAt + 15 min` |
| `created_at` | `BIGINT` | |

### New enum: `InvitationType`

```java
public enum InvitationType { MEMBER, COOP }
```

### Existing entities (no changes)

- `IslandCoop` — used as-is; just needs `CoopRepository` wired in.
- `IslandMember` — used as-is; `MemberRepository` already has all DB primitives.

No new columns on any existing table.

---

## Repository Layer

### `CoopRepository`

Extends `IndexedSyncedRepository<IslandPlayerKey, IslandCoop, UUID>`.

- Index: `islandId → Set<IslandPlayerKey>`
- Secondary map: `playerUuid → islandId` (fast "is this player coop somewhere?")
- L2 (Redis): disabled — mirrors member/role pattern
- Key methods: `findByIsland(UUID)`, `findByPlayer(UUID)`, `isCoop(UUID islandId, UUID playerUuid)`, `add(IslandCoop)`, `remove(UUID islandId, UUID playerUuid)`

### `InvitationRepository`

Extends `SyncedRepository` with L1 (Caffeine) only — invitations are short-lived and don't need Redis coherency.

- Dual index: `islandId → Set<UUID invitationId>` and `recipientId → Set<UUID invitationId>`
- Key methods: `findByIsland(UUID)`, `findByRecipient(UUID)`, `findPending(UUID islandId, UUID recipientId)`, `create(IslandInvitation)`, `delete(UUID)`, `pruneExpired()`

---

## Service Layer

### `InvitationService` (new)

Owns the full invitation lifecycle for both MEMBER and COOP types.

| Method | Description |
|---|---|
| `create(island, senderId, recipientId, InvitationType)` | Validates: recipient not banned, no existing pending invite, team/coop limit not exceeded. Persists, notifies recipient. |
| `accept(islandId, recipientId)` | Resolves type → delegates to `MemberService.addMember` or `CoopService.add`. Deletes invitation. |
| `decline(islandId, recipientId)` | Deletes invitation. Notifies sender. |
| `cancel(island, senderId, targetId)` | Validates sender matches original. Deletes invitation. |
| Scheduler | `pruneExpired()` every 60s. |

### `CoopService` (new)

| Method | Description |
|---|---|
| `add(island, addedBy, playerUuid)` | Validates coop limit. Calls `CoopRepository.add`. Fires `IslandCoopAddEvent`. Broadcasts `CoopAddPacket`. |
| `remove(island, removedBy, playerUuid)` | Removes. Fires `IslandCoopRemoveEvent`. Broadcasts `CoopRemovePacket`. |
| `isCoop(UUID islandId, UUID playerUuid)` | Fast delegate to repository. |
| `findByIsland(UUID)` | Returns all coop entries for an island. |
| `findByPlayer(UUID)` | Returns all islands a player is coop on. |
| `handleCoopAddPacket(CoopAddPacket)` | Updates local cache on receiving server. |
| `handleCoopRemovePacket(CoopRemovePacket)` | Updates local cache on receiving server. |

### `MemberService` (expanded)

Existing read methods unchanged. New write operations:

| Method | Permission check | Description |
|---|---|---|
| `addMember(island, playerUuid, invitedBy)` | None (called by InvitationService after validation) | Calls `MemberRepository.add`. Fires `IslandMemberJoinEvent`. Broadcasts `MemberJoinPacket`. |
| `kick(island, kicker, targetUuid)` | `KICK_MEMBER` + kicker weight > target weight | Removes member. Fires `IslandMemberLeaveEvent(KICKED)`. Broadcasts `MemberLeavePacket`. |
| `leave(island, player)` | Blocks owner | Removes member. Fires `IslandMemberLeaveEvent(VOLUNTARY)`. Broadcasts `MemberLeavePacket`. |
| `promote(island, sender, targetUuid)` | `PROMOTE_MEMBERS` + sender weight > target weight | Sorts island roles by weight ascending, finds the next role above target's current. Calls `MemberRepository.setRole`. `nextRole` does not exist on `IslandRole` — the service computes it by sorting `island.roles()` by weight. |
| `demote(island, sender, targetUuid)` | `DEMOTE_MEMBERS` + sender weight > target weight | Same sort, finds role one step below; blocks demoting below the default (lowest-weight non-system) role. Calls `MemberRepository.setRole`. |
| `transfer(island, currentOwner, newMember)` | Owner only | Calls `MemberRepository.transferOwnership`. Fires event. |
| `handleMemberJoinPacket(MemberJoinPacket)` | — | Updates local player→island cache. |
| `handleMemberLeavePacket(MemberLeavePacket)` | — | Fires `IslandMemberLeaveEvent` locally. Updates cache. |

---

## Events

All extend a base `IslandEvent` (or the closest existing base in the project).

| Event | Fields |
|---|---|
| `IslandMemberJoinEvent` | `Island island, UUID playerId, UUID invitedBy` |
| `IslandMemberLeaveEvent` | `Island island, UUID playerId, LeaveReason reason` (VOLUNTARY / KICKED / BANNED — BANNED reserved for future `BanService`, not fired in this spec) |
| `IslandCoopAddEvent` | `Island island, UUID playerId, UUID addedBy` |
| `IslandCoopRemoveEvent` | `Island island, UUID playerId` |

---

## Cross-Server Packets

Registered in `ASPacketRegistry`.

| Packet | Fields | Channel |
|---|---|---|
| `MemberJoinPacket` | `UUID islandId, UUID playerId, UUID invitedBy` | `MEMBERS` |
| `MemberLeavePacket` | `UUID islandId, UUID playerId, LeaveReason reason` | `MEMBERS` |
| `CoopAddPacket` | `UUID islandId, UUID playerId, UUID addedBy` | `COOPS` |
| `CoopRemovePacket` | `UUID islandId, UUID playerId` | `COOPS` |

---

## Commands

All added as subcommands on `SkyblockCommand` (`@CommandAlias("skyblock|is|island")`).

| Command | Delegates to | Guard |
|---|---|---|
| `/is invite <player>` | `InvitationService.create(MEMBER)` | `INVITE_MEMBER` permission |
| `/is coop <player>` | `InvitationService.create(COOP)` | `COOP_MEMBER` permission |
| `/is accept [player]` | `InvitationService.accept(...)` | Any player (no island needed) |
| `/is decline [player]` | `InvitationService.decline(...)` | Any player |
| `/is cancel <player>` | `InvitationService.cancel(...)` | Sender of the invitation (identity check, no permission required — permissions may have changed since invite was sent) |
| `/is kick <player>` | `MemberService.kick(...)` | `KICK_MEMBER` permission |
| `/is leave` | `MemberService.leave(...)` | Any member; blocks owner |
| `/is promote <player>` | `MemberService.promote(...)` | `PROMOTE_MEMBERS` permission |
| `/is demote <player>` | `MemberService.demote(...)` | `DEMOTE_MEMBERS` permission |
| `/is transfer <player>` | `MemberService.transfer(...)` | Owner only |
| `/is uncoop <player>` | `CoopService.remove(...)` | `UNCOOP_MEMBER` permission |

For `/is accept` and `/is decline`, the optional `[player]` argument is the **sender's name** and resolves which island's invitation to act on when the player has multiple pending invitations. If omitted and only one invite exists, it auto-selects.

---

## GUI Actions

### `action/island/member/`

| Action class | Triggers |
|---|---|
| `InviteMemberAction` | `InvitationService.create(MEMBER)` |
| `KickMemberAction` | `MemberService.kick(...)` |
| `PromoteMemberAction` | `MemberService.promote(...)` |
| `DemoteMemberAction` | `MemberService.demote(...)` |
| `TransferOwnershipAction` | `MemberService.transfer(...)` |

### `action/island/coop/`

| Action class | Triggers |
|---|---|
| `CoopPlayerAction` | `InvitationService.create(COOP)` |
| `UncoopPlayerAction` | `CoopService.remove(...)` |

---

## File Inventory

New files to create:

```
model/member/IslandInvitation.java
model/member/InvitationType.java
repository/CoopRepository.java
repository/InvitationRepository.java
service/CoopService.java
service/InvitationService.java
event/IslandMemberJoinEvent.java
event/IslandMemberLeaveEvent.java
event/IslandCoopAddEvent.java
event/IslandCoopRemoveEvent.java
messaging/packet/island/MemberJoinPacket.java
messaging/packet/island/MemberLeavePacket.java
messaging/packet/island/CoopAddPacket.java
messaging/packet/island/CoopRemovePacket.java
action/island/member/InviteMemberAction.java
action/island/member/KickMemberAction.java
action/island/member/PromoteMemberAction.java
action/island/member/DemoteMemberAction.java
action/island/member/TransferOwnershipAction.java
action/island/coop/CoopPlayerAction.java
action/island/coop/UncoopPlayerAction.java
```

Files modified:

```
service/MemberService.java          (add write operations + packet handlers)
command/SkyblockCommand.java        (add 11 subcommands)
messaging/ASPacketRegistry.java     (register 4 new packets)
```
