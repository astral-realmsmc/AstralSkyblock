# AstralSkyblock GUI Menus — Design

Date: 2026-06-29
Status: Approved design, pending implementation plan

## Goal

Create the GUI menu resource files for AstralSkyblock, mirroring the
[AstralTown](../../../../AstralTown) menu set and informed by SuperiorSkyblock2's
menu types/layouts. **This deliverable is YAML only** — the AstralCore
`MenuContainer` is already wired in `AstralSkyblock` (`this.menus = new MenuContainer(this)`
+ `this.menus.load()`), but no menu YAML exists yet. Java wiring (a `MenuService`,
command hooks, and the custom menu actions) is out of scope for this task and is
captured here only as the contract the menus depend on.

## Framework recap (AstralCore `MenuContainer`)

Menus are YAML files scanned recursively from `src/main/resources/menus/`. Each file:

- Top level: `id`, `title`, `size`, `use-player-inventory`, optional `close-actions`.
- `layouts:` — `{ <name>: { taint: "<taint>", provider: "%placeholder%" } }` drives
  pagination. The provider resolves to a collection; each element is bound to items
  carrying the matching `taints:` entry.
- `items:` — each item has `slot`/`slots`, `material` or `copy-from`, `name`, `lore`,
  optional `glow`, `item-flags`, `priority`, `taints`, `view-requirements`, and one of
  `actions` / `left-click-actions` / `right-click-actions`.

### Action vocabulary

Core actions (provided by AstralCore, work as-is):

- `[open-menu] <id>:k=v:k2=v2` — open another menu with context parameters.
- `[player] <command>` — run a command as the player.
- `[close]` — close the menu.
- `[refresh]`, `[refresh-slot] <n> <delay=1>`, `[refresh-layout] <taint>` — redraw.
- `[next-page] <taint>`, `[previous-page] <taint>` — pagination.
- `[compare] <a> == <b>` (and `!=`) — used in `view-requirements`.

### Placeholder convention

- `%parameters_<key>%` — an object from the open-context `Map` passed to
  `computeAndOpen(player, id, Map.of(...))`. Nested access chains through
  `ComplexPlaceholder.get`, e.g. `%parameters_island_owner_name%`,
  `%parameters_island_members%`.
- `%parameter_<taint>_<field>%` — the current paginated element for a layout taint,
  e.g. `%parameter_member_player_name%`, `%parameter_role_name%`.
- `%layouts_<layoutName>_hasNextPage%` / `hasPreviousPage` — pagination guards.
- `%skyblock_player_island_hasPermission_<PERMISSION>%` — viewer-relative permission
  check used to gate items (mirrors AstralTown's `%towns_player_selected_hasPermission_X%`).

## Domain → placeholder field reference

These are the fields each model's `ComplexPlaceholder.get` actually exposes today.
Menus MUST only reference these (plus the documented gaps below):

- **island**: `id`, `name`, `locked`, `level`, `members` (collection), `owner` (member),
  `roles` (collection), `settings`, `updatedAt`, `createdAt`.
- **member**: `islandId`, `playerId`, `owner`, `role`, `roleId`, `joinedAt`.
  GAP: no `player`/head/name accessor (see Dependencies).
- **role**: `id`, `islandId`, `kind` (MEMBER/VISITOR/COOP), `name`, `weight`,
  `default`, `createdAt`.
- **coop**: `islandId`, `playerId`, `player` (MinecraftPlayerPlaceholder → head/name),
  `addedBy`, `executor`, `createdAt`.
- **ban**: `islandId`, `playerId`, `player`, `bannedBy`, `executor`, `reason`, `createdAt`.
- **blueprint**: (namespace `blueprint`) — used by the creation menu.

## Files (14)

Directory: `src/main/resources/menus/island/` and `.../island/confirm/`.

| File | `id` | Purpose | Layout provider |
|---|---|---|---|
| `island/main.yml` | `island-main` | Control panel. Owner head + island stats (name, level, member count, locked). Buttons → members, roles, settings, coops, bans; each gated by `%skyblock_player_island_hasPermission_*%` with a `*-forbidden` BARRIER fallback (priority pattern from AstralTown). | — |
| `island/members.yml` | `island-members` | Paginated member heads. Owner pinned in slot 1. Click a member → `island-member-manage`. Next/previous-page + bans shortcut. | `parameters_island_members` |
| `island/member-manage.yml` | `island-member-manage` | Single member actions: change role (→ `island-member-role`), kick (→ confirm-kick), ban (→ confirm-ban), transfer ownership. Back → members. | — |
| `island/member-role.yml` | `island-member-role` | Role picker for the selected member; click a role → `[set-member-role]`. | `parameters_island_roles` |
| `island/roles.yml` | `island-roles` | Paginated roles. Left-click → `island-permissions`; right-click → `[edit-role]`. Default role shows a "cannot delete" note. Create button → `[create-role]`. | `parameters_island_roles` |
| `island/permissions.yml` | `island-permissions` | Per-role permission grid. `enabled`/`disabled` item variants gated by `%parameter_permission_enabled%`; click → `[toggle-role-permission]` + `[refresh-layout]`. Persists via `close-actions: [update-role-permissions]`. | `parameters_role_permissions` |
| `island/settings.yml` | `island-settings` | Island flag toggles (PVP, explosions, growth, weather…). `enabled`/`disabled` variants gated by `%parameter_setting_enabled%`; click → `[toggle-island-setting]` + refresh. Persists via `close-actions: [update-island-settings]`. | `parameters_island_settings` |
| `island/coops.yml` | `island-coops` | Paginated coop players. Click → `[uncoop-player]` + `[refresh]`. | `parameters_island_coops` |
| `island/bans.yml` | `island-bans` | Paginated bans (head, reason, date). Click → `[unban-player]` + `[refresh]`. Back → members. | `parameters_island_bans` |
| `island/creation.yml` | `island-creation` | Blueprint picker for players with no island. Click a blueprint → `[create-island]`. | `parameters_blueprints` |
| `island/confirm/confirm-ban.yml` | `island-confirm-ban` | Check/cross dialog. Confirm → `[ban-member]` then back to members. | — |
| `island/confirm/confirm-disband.yml` | `island-confirm-disband` | Confirm → `[disband-island]`. | — |
| `island/confirm/confirm-leave.yml` | `island-confirm-leave` | Confirm → `[leave-island]`. | — |
| `island/confirm/confirm-kick.yml` | `island-confirm-kick` | Confirm → `[kick-member]` then back to members. | — |

### Explicitly dropped

- **`visitors.yml`** — no visitor model/runtime tracking exists. Out of scope.
- **`upgrades.yml`** — no upgrade-definition catalog exists; `IslandUpgrade` only stores
  per-island levels. Out of scope until a catalog is designed.
- **`selection.yml`** — skyblock is one-island-per-player; the no-island flow is
  `creation.yml` only.
- **`warps.yml`** — deferred (not in this 14-file set) though `IslandWarp` data exists;
  a natural follow-up. The control panel does not link to it yet.

## Style fidelity

Match AstralTown exactly:

- French copy.
- MiniMessage color tokens: `<primary_color>`, `<secondary_color>`, `<text_color>`,
  `<information_color>`, plus literal hex (`<#26d971>`) for section headers.
- Titles use CraftEngine image fonts: `%img_internal:neg_8%`, `%img_guis:generic_list%`,
  `<font:alphabet:shift_1>`, etc. (assumes AstralSkyblock loads the same resource pack as
  AstralTown — to be verified at integration; if absent, titles degrade to plain text).
- Icons via `copy-from: "%stacksuppliers_ce_icons:back%"` etc.; player heads via
  `copy-from: "%...head%"`; `item-flags: [HIDE_ATTRIBUTES]` on icon items.
- Standard nav slots in a 54-slot menu: back `49`, previous-page `46`, next-page `52`,
  content slots rows 1–4 (the 35-slot block used by AstralTown lists).

## Dependencies (future Java/config — NOT part of this task)

The YAML references the following, which the later implementation phase must supply:

1. **`IslandMember` `player` placeholder** — add a `case "player" -> new
   MinecraftPlayerPlaceholder(playerUuid)` (and the `head`/`name` chain it provides) to
   `IslandMember.get`, so member heads/names render. Coop and ban already expose this.
2. **Custom menu actions** (registered in the `action` package):
   `[set-member-role]`, `[cycle-member-role]`, `[kick-member]`, `[ban-member]`,
   `[toggle-island-setting]`, `[update-island-settings]`, `[toggle-role-permission]`,
   `[update-role-permissions]`, `[create-role]`, `[edit-role]`, `[uncoop-player]`,
   `[unban-player]`, `[disband-island]`, `[leave-island]`, `[create-island]`.
3. **`skyblock` placeholder expansion** exposing `%skyblock_player_island_hasPermission_<PERM>%`
   for viewer-relative item gating.
4. **A `MenuService`** (mirroring `AstralTown`'s) plus command/subcommand hooks that call
   `computeAndOpen(player, "<id>", Map.of(...))` with the parameter keys named above.
5. **Provider placeholders** the layouts read:
   - `parameters_island_settings` and `parameters_role_permissions` must resolve to the
     toggle entries (each exposing `_item`, `_enabled`, and the enum value);
   - `parameters_blueprints` to the blueprint collection;
   - `parameters_island_coops` and `parameters_island_bans` to the island's coop/ban
     collections. NOTE: `Island.get()` currently exposes neither — these must be added
     (as `island` placeholder cases or as open-context map entries supplied by the
     `MenuService` when opening `island-coops` / `island-bans`), or those two lists render
     empty. The per-element fields the menus read (`coop.player`/`playerId`/`executor`,
     `ban.player`/`playerId`/`reason`/`executor`) already exist on those models.

These are documented as the contract; the menus are authored against it so that the Java
phase is a fill-in rather than a redesign.

## Testing

No automated tests (static resource files). Validation is:

1. YAML parses (load via `MenuContainer.load()` at plugin startup — no errors logged).
2. Manual smoke test once the Java contract above is implemented: open each menu,
   verify navigation, pagination, toggles, and confirm flows.
