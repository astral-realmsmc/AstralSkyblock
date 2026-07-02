# Member Management Messages — Design

**Date:** 2026-07-02
**Status:** Approved

## Goal

Give member management (kick, leave, promote, demote, transfer) the same configurable
messaging treatment as invitations: all user-facing feedback flows through `ASMessages`
keys defined in `messages.yml`, sent from the service layer so both commands and GUI
actions get feedback, with cross-server notification of the affected player via
`ChatService`.

## Background

- `MemberService` write methods silently return on failed checks (no permission, target
  outranks sender, already at top/bottom of the role ladder, owner can't leave).
- `MemberCommand` prints hardcoded `Component.text` messages and reports success
  unconditionally in `.thenAccept(...)` — even when the service no-oped.
- GUI actions (`KickMemberAction`, `PromoteMemberAction`, `DemoteMemberAction`,
  `TransferOwnershipAction`) call the service directly and give no feedback at all.

Decision (user-approved): **full invitation parity** — the service messages every
outcome; commands keep only precondition checks.

## New message keys (`messages.yml` `# Members` section)

```yaml
member-not-found: "%target_name% is not a member of %island_name%."
member-higher-role: "You cannot do that to a member whose role is equal to or higher than yours."
member-kicked-sender: "%target_name% has been kicked from %island_name%."
member-kicked-target: "You have been kicked from %island_name%."
island-left: "You have left %island_name%."
owner-cannot-leave: "You cannot leave your own island. Transfer ownership first."
member-promoted-sender: "%target_name% has been promoted to %role_name%."
member-promoted-target: "You have been promoted to %role_name% in %island_name%."
member-already-highest-role: "%target_name% is already at the highest role."
member-promote-higher: "You cannot promote a member to a role equal to or higher than your own."
member-demoted-sender: "%target_name% has been demoted to %role_name%."
member-demoted-target: "You have been demoted to %role_name% in %island_name%."
member-already-lowest-role: "%target_name% is already at the lowest role."
not-island-owner: "Only the island owner can do this."
ownership-transferred-sender: "Island ownership has been transferred to %target_name%."
ownership-transferred-target: "You are now the owner of %island_name%."
```

Matching `CONSTANT_CASE` enum constants go in `ASMessages` (`ConfigurationManager.formatKey`
maps `MEMBER_KICKED_SENDER` → `member-kicked-sender`).

## MemberService wiring

Each write method builds a `PlaceholderContainer` the way `InvitationService` does:
base player = acting player (`%player_name%`), `registerPlaceholder(island)`
(`%island_name%`), `registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid))`
(`%target_name%`), and for promote/demote the new role via `registerPlaceholder(role)`
(`%role_name%`, `IslandRole.namespace()` is `role`).

- **kick** — failures: `NO_PERMISSION`, `MEMBER_NOT_FOUND`, `MEMBER_HIGHER_ROLE` (owner
  target or outrank failure). Success (`whenComplete`): keep event + packet, then
  `MEMBER_KICKED_SENDER` to kicker and `MEMBER_KICKED_TARGET` to the kicked player via
  `ChatService`. Repository exception → `UNEXPECTED_ERROR` + SLF4J error log.
- **leave** — not a member → `NO_ISLAND`; owner → `OWNER_CANNOT_LEAVE`. Success:
  `ISLAND_LEFT`.
- **promote** — `NO_PERMISSION` (missing permission or sender not a member),
  `MEMBER_NOT_FOUND`, `MEMBER_HIGHER_ROLE` (owner target), `MEMBER_ALREADY_HIGHEST_ROLE`
  (top of ladder), `MEMBER_PROMOTE_HIGHER` (next role ≥ sender's weight). Success:
  `MEMBER_PROMOTED_SENDER` + `MEMBER_PROMOTED_TARGET` (cross-server) with the new role
  registered for `%role_name%`.
- **demote** — mirror of promote: `MEMBER_ALREADY_LOWEST_ROLE`, `MEMBER_HIGHER_ROLE`
  for outrank failures. Success messages carry the new (lower) role.
- **transfer** — non-owner caller → `NOT_ISLAND_OWNER`. Success:
  `OWNERSHIP_TRANSFERRED_SENDER` + `OWNERSHIP_TRANSFERRED_TARGET` (cross-server).
  Exception or `false` from the transactional repository call → `UNEXPECTED_ERROR`.
- **addMember** — unchanged; the invitation flow already messages that path.

## MemberCommand cleanup

- Hardcoded "You don't have an island." → `NO_ISLAND` (kick, leave, promote, demote).
- Drop all unconditional `.thenAccept(... success message ...)` calls — the service now
  reports outcomes truthfully.
- Transfer preconditions split: `NO_ISLAND` (no island), `NOT_ISLAND_OWNER` (not owner),
  `MEMBER_NOT_FOUND` (target not a member, with island+target placeholders).
- Permission pre-checks stay (defense in depth; service re-checks for GUI callers).

## Coops (same-day extension)

Same treatment applied to coop removal (coop additions already flow through the fully
messaged invitation accept path, and `CoopService.add` stays silent like `addMember`):

```yaml
# Coops
coop-not-found: "%target_name% is not a co-op member of %island_name%."
coop-removed-sender: "%target_name% is no longer a co-op member of %island_name%."
coop-removed-target: "You are no longer a co-op member of %island_name%."
```

- `CoopService.remove` gains the acting player: `remove(Island, Player remover, UUID)`.
  It now checks `UNCOOP_MEMBER` (→ `NO_PERMISSION`) and coop membership
  (→ `COOP_NOT_FOUND`), and on success messages the remover and notifies the removed
  player cross-server; repository failure → `UNEXPECTED_ERROR` + log.
- `CoopCommand.onUncoop` keeps `NO_ISLAND`/`NO_PERMISSION` preconditions and delegates;
  hardcoded strings and the unconditional success message removed.
- `UncoopPlayerAction` drops its silent permission pre-check (service messages now),
  matching `KickMemberAction`.
- `InvitationCommand.onCoop` drops its hardcoded "Coop invitation sent" message —
  `InvitationService` already sends `invitation-sent`.

## Out of scope

- The two pre-existing compile errors in `CoopPlayerAction` / `InviteMemberAction`
  (UUID passed where `InvitationService.create` expects `Player`) — fixed separately
  by the user while this work was in flight.

## Verification

Project has no tests and `mvn compile` is blocked by the two pre-existing errors above;
verification = compiler reports no errors in the touched files.
