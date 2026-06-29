# AstralSkyblock Menu Actions

Custom menu actions referenced by the island GUI menus (`src/main/resources/menus/island/`)
that still need Java implementations. Derived from a grep of all 14 menu files.

Each custom action is a `record` implementing AstralCore's `PaperAction`, with a constructor
taking `PlaceholderWrapper<>` args parsed from the action's arguments, registered via
`registerAction` in `AstralSkyblock` (same pattern as AstralTown's `action` package).

## Already provided by AstralCore (no code needed)

`[open-menu]`, `[compare]`, `[close]`, `[refresh]`, `[refresh-slot]`, `[refresh-layout]`,
`[next-page]`, `[previous-page]`, `[player]`

## Missing custom actions (14)

| # | Action | Used in | Arguments | Must do | AstralTown analog to adapt |
|---|--------|---------|-----------|---------|----------------------------|
| 1 | `[toggle-island-setting]` | settings.yml | `<islandId> <setting>` | Flip one `IslandSettings` flag in memory (not yet persisted) | `ToggleTownSettingsAction` ✅ |
| 2 | `[update-island-settings]` | settings.yml (close-action) | `<islandId>` | Persist the toggled settings set to DB on menu close | `UpdateTownSettingsAction` ✅ |
| 3 | `[toggle-role-permission]` | permissions.yml | `<islandId> <roleId> <permission>` | Flip one `IslandPermission` on the role in memory | `ToggleRolePermissionAction` ✅ |
| 4 | `[update-role-permissions]` | permissions.yml (close-action) | `<islandId> <roleId>` | Persist the role's permission set on close | `UpdateRolePermissionAction` ✅ |
| 5 | `[set-member-role]` | member-role.yml | `<islandId> <playerId> <roleId>` | Assign a role to a member (direct set) | — (town *cycles* instead; closest: `CycleTownMemberRoleAction`) |
| 6 | `[create-role]` | roles.yml | `<islandId>` | Create a new role (likely opens a name dialog) | `CreateRoleAction` ✅ |
| 7 | `[edit-role]` | roles.yml (right-click) | `<islandId> <roleId>` | Edit/rename a role (dialog) | `EditRoleAction` ✅ |
| 8 | `[kick-member]` | confirm-kick.yml | `<islandId> <playerId>` | Remove a member from the island | — (town has `KickSubZoneMemberAction`, different scope) |
| 9 | `[ban-member]` | confirm-ban.yml | `<islandId> <playerId>` | Ban a member (uses `BanService` / `IslandBan`) | — |
| 10 | `[unban-player]` | bans.yml | `<islandId> <playerId>` | Remove a ban | `UnbanPlayerAction` ✅ |
| 11 | `[uncoop-player]` | coops.yml | `<islandId> <playerId>` | Remove a coop entry | — |
| 12 | `[leave-island]` | confirm-leave.yml | `<islandId>` | Current player leaves the island | — |
| 13 | `[disband-island]` | confirm-disband.yml | `<islandId>` | Delete the island | — (analogous to `DeleteSubZoneAction`) |
| 14 | `[create-island]` | creation.yml | `<blueprintId>` | Create a new island from a blueprint | — |

## Notes

- **6 are near-direct ports** from AstralTown (✅ column): settings toggle/update, permission
  toggle/update, create-role, edit-role, unban — adapt the town class swapping `Town` → `Island`.
- **8 are skyblock-specific** with no town equivalent: `set-member-role`, `kick-member`,
  `ban-member`, `uncoop-player`, `leave-island`, `disband-island`, `create-island` (and
  `set-member-role` diverges from town's cycle pattern). These map onto existing services —
  `MemberService` (set-role / kick / leave), `BanService` (ban / unban), an `IslandCoop` repo
  (uncoop), `IslandService` (create / disband).
- Actions 5, 8, 9 use `<playerId>` (the `IslandMember.playerId` field) — consistent with the
  confirm/role menus passing `%parameters_member_playerId%`.
