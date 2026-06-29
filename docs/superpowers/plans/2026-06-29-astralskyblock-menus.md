# AstralSkyblock GUI Menus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author the 14 AstralCore `MenuContainer` YAML menu files for AstralSkyblock (control panel, member/role/permission/settings management, coop/ban lists, blueprint creation, and confirm dialogs), mirroring the AstralTown menu set.

**Architecture:** Pure resource files under `src/main/resources/menus/island/`. The `MenuContainer` is already wired (`AstralSkyblock` constructs it and calls `.load()`), and scans `menus/` recursively, so new files are picked up with no Java changes. Each file is a self-contained menu identified by its `id`, opened later via `computeAndOpen(player, id, Map.of(...))`. Menus are authored against the documented contract in the design spec (custom actions, the `IslandMember.player` placeholder, the `skyblock_player_island_hasPermission_*` expansion, and provider placeholders) — that Java contract is **out of scope** for this plan.

**Tech Stack:** YAML, AstralCore menu framework, MiniMessage formatting, CraftEngine image-font resource pack (shared with AstralTown).

## Global Constraints

- **Deliverable is YAML only.** No `.java` changes, no edits to `AstralSkyblock.java`. Do not implement the custom actions or placeholders the menus reference — they are a documented future contract.
- **Language:** French copy, verbatim tone/structure from AstralTown menus.
- **Formatting tokens (copy verbatim):** color tokens `<primary_color>`, `<secondary_color>`, `<text_color>`, `<information_color>`; literal hex section headers `<#26d971>` / `<#e3dbcc>`; click hints `<white>%img_icons:left-click%`, `<white>%img_icons:right-click%`.
- **Icons:** `copy-from: "%stacksuppliers_ce_icons:<name>%"` (known names: `back`, `addition`, `check`, `cross`, `ban`, `thin_back`, `thin_next`, `info`, `home`, `scroll`); player heads via `copy-from: "%...head%"`; add `item-flags: [HIDE_ATTRIBUTES]` to icon items.
- **Titles:** `"%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_1XX%<font:alphabet:shift_1><name>"` pattern for list menus; control panel / special menus use a dedicated `%img_guis:*%` glyph.
- **Layout:** size `54`, `use-player-inventory: true`. Standard nav: back `49`, previous-page `46`, next-page `52`. List content uses AstralTown's 35-slot block: `1-7,10-16,19-25,28-34,37-43`.
- **Placeholder convention:** `%parameters_<key>%` = open-context object; `%parameter_<taint>_<field>%` = current paginated element; `%layouts_<name>_hasNextPage%`/`hasPreviousPage` = pagination guards.
- **Validation per file:** `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" <path>` must exit 0.
- **Reference fields only** the model exposes (see spec's "Domain → placeholder field reference"). The one sanctioned forward-reference is `%parameter_member_player_*%` (the documented `IslandMember.player` gap) — use it; do not invent other fields.

---

### Task 1: Scaffold + control panel (`main.yml`)

Establishes the directory and the canonical style/nav conventions every later file copies.

**Files:**
- Create: `src/main/resources/menus/island/main.yml`

**Interfaces:**
- Produces: menu id `island-main`, opened with `Map.of("island", island)` → referenced as `%parameters_island%`. Buttons emit `[open-menu] island-members:island=%parameters_island%` (and `island-roles`, `island-settings`, `island-coops`, `island-bans`). Consumed by every menu's `back` button (`[open-menu] island-main:island=%parameters_island%`).
- Consumes: nothing (entry point).

- [ ] **Step 1: Create `main.yml`**

```yaml
id: "island-main"
title: "%img_internal:neg_8%<white>%img_guis:towns%"
size: 54
use-player-inventory: true

items:
  # Selected island
  island:
    slot: 4
    copy-from: "%parameters_island_owner_player_head%"
    name: "<bold><primary_color>%parameters_island_name%"
    lore:
      - ""
      - "<#26d971><bold>| Informations"
      - "  <gray>- <#e3dbcc>Chef : <secondary_color>%parameters_island_owner_player_name%"
      - "  <gray>- <#e3dbcc>Niveau : <secondary_color>%parameters_island_level%"
      - "  <gray>- <#e3dbcc>Membres : <secondary_color>%parameters_island_members_size%"
      - ""
      - "<white>%img_icons:left-click% <information_color>Pour vous téléporter"
      - "<secondary_color><italic>Raccourci</italic> <information_color>: /is go"
    actions:
      - "[player] is go"
  # Members
  members:
    slot: 20
    material: "PLAYER_HEAD"
    priority: 2
    name: "<bold><primary_color>Membres"
    lore:
      - ""
      - "  <text_color>Permet de voir les <secondary_color>membres"
      - "  <text_color>présents sur l'<secondary_color>île<text_color>."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le menu des membres"
    actions:
      - "[open-menu] island-members:island=%parameters_island%"
  # Roles
  roles:
    slot: 21
    material: "BOOK"
    priority: 2
    name: "<bold><primary_color>Rôles"
    lore:
      - ""
      - "  <text_color>Permet de modifier les <secondary_color>rôles"
      - "  <text_color>de l'<secondary_color>île<text_color>."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le menu des rôles"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_SET_ROLE% == true"
    actions:
      - "[open-menu] island-roles:island=%parameters_island%"
  roles-forbidden:
    slot: 21
    material: "BARRIER"
    name: "<bold><primary_color>Rôles"
    lore:
      - ""
      - "<red>Vous n'avez pas la permission"
      - "<red>d'accéder à ce menu."
  # Settings
  settings:
    slot: 22
    material: "REDSTONE"
    priority: 2
    name: "<bold><primary_color>Paramètres de l'île"
    lore:
      - ""
      - "  <text_color>Permet de modifier les <secondary_color>paramètres <text_color>de l'<secondary_color>île."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le menu de paramètres"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_SET_SETTINGS% == true"
    actions:
      - "[open-menu] island-settings:island=%parameters_island%"
  settings-forbidden:
    slot: 22
    material: "BARRIER"
    name: "<bold><primary_color>Paramètres de l'île"
    lore:
      - ""
      - "<red>Vous n'avez pas la permission"
      - "<red>d'accéder à ce menu."
  # Coops
  coops:
    slot: 23
    material: "LEAD"
    priority: 2
    name: "<bold><primary_color>Coopérateurs"
    lore:
      - ""
      - "  <text_color>Permet de gérer les <secondary_color>coopérateurs"
      - "  <text_color>de l'<secondary_color>île<text_color>."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le menu des coopérateurs"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_COOP_MEMBER% == true"
    actions:
      - "[open-menu] island-coops:island=%parameters_island%"
  coops-forbidden:
    slot: 23
    material: "BARRIER"
    name: "<bold><primary_color>Coopérateurs"
    lore:
      - ""
      - "<red>Vous n'avez pas la permission"
      - "<red>d'accéder à ce menu."
  # Bans
  bans:
    slot: 24
    copy-from: "%stacksuppliers_ce_icons:ban%"
    priority: 2
    name: "<bold><primary_color>Joueurs bannis"
    item-flags:
      - HIDE_ATTRIBUTES
    lore:
      - ""
      - "  <text_color>Permet de gérer les <secondary_color>joueurs bannis"
      - "  <text_color>de l'<secondary_color>île<text_color>."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le menu des bannis"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_BAN_MEMBER% == true"
    actions:
      - "[open-menu] island-bans:island=%parameters_island%"
  bans-forbidden:
    slot: 24
    material: "BARRIER"
    name: "<bold><primary_color>Joueurs bannis"
    lore:
      - ""
      - "<red>Vous n'avez pas la permission"
      - "<red>d'accéder à ce menu."
```

- [ ] **Step 2: Validate YAML parses**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" src/main/resources/menus/island/main.yml`
Expected: exit 0, no output.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/menus/island/main.yml
git commit -m "feat(menus): add island control panel menu"
```

---

### Task 2: Member browsing & management flow

Three files: the paginated member list, the per-member action menu, and the role picker.

**Files:**
- Create: `src/main/resources/menus/island/members.yml`
- Create: `src/main/resources/menus/island/member-manage.yml`
- Create: `src/main/resources/menus/island/member-role.yml`

**Interfaces:**
- Consumes: `island-main` opens `island-members` with `island=%parameters_island%`.
- Produces:
  - `island-members` — layout taint `member` over `%parameters_island_members%`; each member opens `island-member-manage:island=%parameters_island%:member=%parameter_member%`.
  - `island-member-manage` — context keys `island`, `member`; opens `island-member-role`, `island-confirm-kick`, `island-confirm-ban`.
  - `island-member-role` — context keys `island`, `member`; layout taint `role` over `%parameters_island_roles%`; click → `[set-member-role] %parameters_island_id% %parameters_member_id% %parameter_role_id%`.

- [ ] **Step 1: Create `members.yml`**

```yaml
id: "island-members"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_107%<font:alphabet:shift_1>membres"
size: 54
use-player-inventory: true

layouts:
  members:
    taint: "member"
    provider: "%parameters_island_members%"

items:
  member:
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_member_player_head%"
    name: "<primary_color><bold>%parameter_member_player_name%"
    lore:
      - ""
      - "  <text_color>Rôle : <secondary_color>%parameter_member_role_name%"
      - ""
      - "<white>%img_icons:left-click% <gray>Pour gérer ce membre"
    taints:
      - "member"
    actions:
      - "[open-menu] island-member-manage:island=%parameters_island%:member=%parameter_member%"

  bans:
    slot: 50
    copy-from: "%stacksuppliers_ce_icons:ban%"
    name: "<bold><primary_color>Joueurs bannis"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-bans:island=%parameters_island%"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"

  previous-page:
    slot: 46
    copy-from: "%stacksuppliers_ce_icons:thin_back%"
    name: "<primary_color><bold>Page précédente"
    actions:
      - "[previous-page] member"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_members_hasPreviousPage% == true"

  next-page:
    slot: 52
    copy-from: "%stacksuppliers_ce_icons:thin_next%"
    name: "<primary_color><bold>Page suivante"
    actions:
      - "[next-page] member"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_members_hasNextPage% == true"
```

- [ ] **Step 2: Create `member-manage.yml`**

```yaml
id: "island-member-manage"
title: "%img_internal:neg_8%<white>%img_guis:generic_27%%img_internal:neg_176%%img_title%%img_internal:neg_111%<font:alphabet:alphabet>membre"
size: 54
use-player-inventory: true

items:
  member:
    slot: 4
    copy-from: "%parameters_member_player_head%"
    name: "<primary_color><bold>%parameters_member_player_name%"
    lore:
      - ""
      - "  <text_color>Rôle : <secondary_color>%parameters_member_role_name%"

  role:
    slot: 20
    material: "BOOK"
    name: "<bold><primary_color>Changer de rôle"
    lore:
      - ""
      - "  <text_color>Permet d'attribuer un <secondary_color>rôle <text_color>à ce membre."
      - ""
      - "<white>%img_icons:left-click% <information_color>Ouvrir le sélecteur de rôle"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_SET_ROLE% == true"
    actions:
      - "[open-menu] island-member-role:island=%parameters_island%:member=%parameters_member%"

  kick:
    slot: 22
    material: "IRON_DOOR"
    name: "<bold><red>Expulser"
    lore:
      - ""
      - "  <text_color>Permet d'<secondary_color>expulser <text_color>ce membre de l'île."
      - ""
      - "<white>%img_icons:left-click% <information_color>Expulser ce membre"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_KICK_MEMBER% == true"
    actions:
      - "[open-menu] island-confirm-kick:island=%parameters_island%:member=%parameters_member%"

  ban:
    slot: 24
    copy-from: "%stacksuppliers_ce_icons:ban%"
    name: "<bold><red>Bannir"
    item-flags:
      - HIDE_ATTRIBUTES
    lore:
      - ""
      - "  <text_color>Permet de <secondary_color>bannir <text_color>ce membre de l'île."
      - ""
      - "<white>%img_icons:left-click% <information_color>Bannir ce membre"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_BAN_MEMBER% == true"
    actions:
      - "[open-menu] island-confirm-ban:island=%parameters_island%:member=%parameters_member%"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-members:island=%parameters_island%"
```

- [ ] **Step 3: Create `member-role.yml`**

```yaml
id: "island-member-role"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_103%<font:alphabet:shift_1>roles"
size: 54
use-player-inventory: true

layouts:
  roles:
    taint: "role"
    provider: "%parameters_island_roles%"

items:
  role:
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    material: "player_head"
    name: "<primary_color><bold>%parameter_role_name%"
    lore:
      - ""
      - "  <text_color>Poids : <secondary_color>%parameter_role_weight%"
      - ""
      - "<white>%img_icons:left-click% <gray>Attribuer ce rôle"
    taints:
      - "role"
    actions:
      - "[set-member-role] %parameters_island_id% %parameters_member_playerId% %parameter_role_id%"
      - "[open-menu] island-member-manage:island=%parameters_island%:member=%parameters_member%"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-member-manage:island=%parameters_island%:member=%parameters_member%"
```

- [ ] **Step 4: Validate all three parse**

Run:
```bash
for f in members member-manage member-role; do python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "src/main/resources/menus/island/$f.yml" && echo "$f ok"; done
```
Expected: `members ok` / `member-manage ok` / `member-role ok`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/menus/island/members.yml src/main/resources/menus/island/member-manage.yml src/main/resources/menus/island/member-role.yml
git commit -m "feat(menus): add member browsing and management menus"
```

---

### Task 3: Roles & permissions editing

The role list (left=edit perms, right=edit role, plus create) and the per-role permission toggle grid.

**Files:**
- Create: `src/main/resources/menus/island/roles.yml`
- Create: `src/main/resources/menus/island/permissions.yml`

**Interfaces:**
- Consumes: `island-main` and `member-role` reference `island-roles`. `roles` opens `island-permissions` with `island` + `role`.
- Produces:
  - `island-roles` — context key `island`; layout taint `role` over `%parameters_island_roles%`; left-click → `island-permissions`; right-click → `[edit-role]`; create → `[create-role]`.
  - `island-permissions` — context keys `island`, `role`; layout taint `permission` over `%parameters_role_permissions%`; toggle → `[toggle-role-permission]`; `close-actions` → `[update-role-permissions]`.

- [ ] **Step 1: Create `roles.yml`**

```yaml
id: "island-roles"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_103%<font:alphabet:shift_1>roles"
size: 54
use-player-inventory: true

layouts:
  roles:
    taint: "role"
    provider: "%parameters_island_roles%"

items:
  default-role:
    priority: 2
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    material: "player_head"
    name: "<primary_color><bold>%parameter_role_name%"
    lore:
      - ""
      - "  <text_color>Poids : <secondary_color>%parameter_role_weight%"
      - ""
      - "<warning>Vous ne pouvez pas supprimer ce rôle par défaut"
    taints:
      - "role"
    view-requirements:
      - "[compare] %parameter_role_default% == true"
    actions:
      - "[open-menu] island-permissions:island=%parameters_island%:role=%parameter_role%"

  role:
    priority: 1
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    material: "player_head"
    name: "<primary_color><bold>%parameter_role_name%"
    lore:
      - ""
      - "  <text_color>Poids : <secondary_color>%parameter_role_weight%"
      - ""
      - "<white>%img_icons:left-click% <gray>Modifier les permissions"
      - "<white>%img_icons:right-click% <gray>Modifier le rôle"
    taints:
      - "role"
    left-click-actions:
      - "[open-menu] island-permissions:island=%parameters_island%:role=%parameter_role%"
    right-click-actions:
      - "[edit-role] %parameters_island_id% %parameter_role_id%"

  create:
    slot: 48
    copy-from: "%stacksuppliers_ce_icons:addition%"
    name: "<bold><primary_color>Créer un nouveau rôle"
    actions:
      - "[close]"
      - "[create-role] %parameters_island_id%"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"
```

- [ ] **Step 2: Create `permissions.yml`**

```yaml
id: "island-permissions"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_116%<font:alphabet:shift_1>permissions"
size: 54
use-player-inventory: true

layouts:
  permissions:
    taint: "permission"
    provider: "%parameters_role_permissions%"

close-actions:
  - "[update-role-permissions] %parameters_island_id% %parameters_role_id%"

items:
  permission-enabled:
    priority: 1
    glow: true
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_permission_item%"
    lore:
      - ""
      - "<secondary_color>[Statut] <information_color>: <green>Activé"
      - ""
      - "<white>%img_icons:left-click% <information_color>pour désactiver"
    taints:
      - "permission"
    view-requirements:
      - "[compare] %parameter_permission_enabled% == true"
    actions:
      - "[toggle-role-permission] %parameters_island_id% %parameters_role_id% %parameter_permission%"
      - "[refresh-layout] permission"

  permission-disabled:
    priority: 1
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_permission_item%"
    lore:
      - ""
      - "<secondary_color>[Statut] <information_color>: <red>Désactivé"
      - ""
      - "<white>%img_icons:left-click% <information_color>pour activer"
    taints:
      - "permission"
    view-requirements:
      - "[compare] %parameter_permission_enabled% == false"
    actions:
      - "[toggle-role-permission] %parameters_island_id% %parameters_role_id% %parameter_permission%"
      - "[refresh-layout] permission"

  previous-page:
    slot: 46
    copy-from: "%stacksuppliers_ce_icons:thin_back%"
    name: "<primary_color><bold>Page précédente"
    actions:
      - "[previous-page] permission"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_permissions_hasPreviousPage% == true"

  next-page:
    slot: 52
    copy-from: "%stacksuppliers_ce_icons:thin_next%"
    name: "<primary_color><bold>Page suivante"
    actions:
      - "[next-page] permission"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_permissions_hasNextPage% == true"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-roles:island=%parameters_island%"
```

- [ ] **Step 3: Validate both parse**

Run:
```bash
for f in roles permissions; do python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "src/main/resources/menus/island/$f.yml" && echo "$f ok"; done
```
Expected: `roles ok` / `permissions ok`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/menus/island/roles.yml src/main/resources/menus/island/permissions.yml
git commit -m "feat(menus): add role list and permission editor menus"
```

---

### Task 4: Island settings toggles (`settings.yml`)

Island flag toggle grid with enabled/disabled variants, persisting on close.

**Files:**
- Create: `src/main/resources/menus/island/settings.yml`

**Interfaces:**
- Consumes: `island-main` opens `island-settings` with `island=%parameters_island%`.
- Produces: `island-settings` — context key `island`; layout taint `setting` over `%parameters_island_settings%`; toggle → `[toggle-island-setting]`; `close-actions` → `[update-island-settings]`.

- [ ] **Step 1: Create `settings.yml`**

```yaml
id: "island-settings"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_117%<font:alphabet:shift_1>parametres"
size: 54
use-player-inventory: true

layouts:
  settings:
    taint: "setting"
    provider: "%parameters_island_settings%"

close-actions:
  - "[update-island-settings] %parameters_island_id%"

items:
  setting-enabled:
    glow: true
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_setting_item%"
    lore:
      - ""
      - "<secondary_color>[Statut] <information_color>: <green>Activé"
      - ""
      - "<white>%img_icons:right-click% <information_color>pour désactiver"
    taints:
      - "setting"
    view-requirements:
      - "[compare] %parameter_setting_enabled% == true"
    actions:
      - "[toggle-island-setting] %parameters_island_id% %parameter_setting%"
      - "[refresh-layout] setting"

  setting-disabled:
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_setting_item%"
    lore:
      - ""
      - "<secondary_color>[Statut] <information_color>: <red>Désactivé"
      - ""
      - "<white>%img_icons:right-click% <information_color>pour activer"
    taints:
      - "setting"
    view-requirements:
      - "[compare] %parameter_setting_enabled% == false"
    actions:
      - "[toggle-island-setting] %parameters_island_id% %parameter_setting%"
      - "[refresh-layout] setting"

  previous-page:
    slot: 46
    copy-from: "%stacksuppliers_ce_icons:thin_back%"
    name: "<primary_color><bold>Page précédente"
    actions:
      - "[previous-page] setting"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_settings_hasPreviousPage% == true"

  next-page:
    slot: 52
    copy-from: "%stacksuppliers_ce_icons:thin_next%"
    name: "<primary_color><bold>Page suivante"
    actions:
      - "[next-page] setting"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_settings_hasNextPage% == true"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"
```

- [ ] **Step 2: Validate parse**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" src/main/resources/menus/island/settings.yml`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/menus/island/settings.yml
git commit -m "feat(menus): add island settings toggle menu"
```

---

### Task 5: Coop & ban lists

Two paginated relationship lists. Coops use the `coop` namespace's `player` accessor; bans use the `ban` namespace's `player` + `reason`.

**Files:**
- Create: `src/main/resources/menus/island/coops.yml`
- Create: `src/main/resources/menus/island/bans.yml`

**Interfaces:**
- Consumes: `island-main` opens `island-coops` and `island-bans`; `members`/`member-manage` also reach `island-bans`.
- Produces:
  - `island-coops` — context key `island`; layout taint `coop` over `%parameters_island_coops%`; click → `[uncoop-player]` + `[refresh]`.
  - `island-bans` — context key `island`; layout taint `ban` over `%parameters_island_bans%`; click → `[unban-player]` + `[refresh]`.

- [ ] **Step 1: Create `coops.yml`**

```yaml
id: "island-coops"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_100%<font:alphabet:shift_1>coops"
size: 54
use-player-inventory: true

layouts:
  coops:
    taint: "coop"
    provider: "%parameters_island_coops%"

items:
  coop:
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_coop_player_head%"
    name: "<primary_color><bold>%parameter_coop_player_name%"
    lore:
      - ""
      - "  <text_color>Ajouté par : <secondary_color>%parameter_coop_executor_name%"
      - ""
      - "<white>%img_icons:left-click% <gray>Retirer ce coopérateur"
    taints:
      - "coop"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_UNCOOP_MEMBER% == true"
    actions:
      - "[uncoop-player] %parameters_island_id% %parameter_coop_playerId%"
      - "[refresh]"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"

  previous-page:
    slot: 46
    copy-from: "%stacksuppliers_ce_icons:thin_back%"
    name: "<primary_color><bold>Page précédente"
    actions:
      - "[previous-page] coop"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_coops_hasPreviousPage% == true"

  next-page:
    slot: 52
    copy-from: "%stacksuppliers_ce_icons:thin_next%"
    name: "<primary_color><bold>Page suivante"
    actions:
      - "[next-page] coop"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_coops_hasNextPage% == true"
```

- [ ] **Step 2: Create `bans.yml`**

```yaml
id: "island-bans"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_100%<font:alphabet:shift_1>bans"
size: 54
use-player-inventory: true

layouts:
  bans:
    taint: "ban"
    provider: "%parameters_island_bans%"

items:
  ban:
    slots: [1,2,3,4,5,6,7,10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
    copy-from: "%parameter_ban_player_head%"
    name: "<primary_color><bold>%parameter_ban_player_name%"
    lore:
      - ""
      - "  <text_color>Raison : <secondary_color>%parameter_ban_reason%"
      - "  <text_color>Banni par : <secondary_color>%parameter_ban_executor_name%"
      - ""
      - "<white>%img_icons:left-click% <gray>Débannir ce joueur"
    taints:
      - "ban"
    view-requirements:
      - "[compare] %skyblock_player_island_hasPermission_BAN_MEMBER% == true"
    actions:
      - "[unban-player] %parameters_island_id% %parameter_ban_playerId%"
      - "[refresh]"

  back:
    slot: 49
    copy-from: "%stacksuppliers_ce_icons:back%"
    name: "<bold><primary_color>Retour"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-members:island=%parameters_island%"

  previous-page:
    slot: 46
    copy-from: "%stacksuppliers_ce_icons:thin_back%"
    name: "<primary_color><bold>Page précédente"
    actions:
      - "[previous-page] ban"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_bans_hasPreviousPage% == true"

  next-page:
    slot: 52
    copy-from: "%stacksuppliers_ce_icons:thin_next%"
    name: "<primary_color><bold>Page suivante"
    actions:
      - "[next-page] ban"
      - "[refresh-slot] 52 <delay=1>"
      - "[refresh-slot] 46 <delay=1>"
    view-requirements:
      - "[compare] %layouts_bans_hasNextPage% == true"
```

- [ ] **Step 3: Validate both parse**

Run:
```bash
for f in coops bans; do python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "src/main/resources/menus/island/$f.yml" && echo "$f ok"; done
```
Expected: `coops ok` / `bans ok`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/menus/island/coops.yml src/main/resources/menus/island/bans.yml
git commit -m "feat(menus): add coop and ban list menus"
```

---

### Task 6: Island creation (`creation.yml`)

Blueprint picker — the no-island entry menu.

**Files:**
- Create: `src/main/resources/menus/island/creation.yml`

**Interfaces:**
- Consumes: opened with `Map.of("blueprints", <collection>)` → `%parameters_blueprints%`. No `island` context (player has none).
- Produces: `island-creation` — layout taint `blueprint` over `%parameters_blueprints%`; click → `[create-island] %parameter_blueprint_id%`. The `blueprint` namespace exposes `id` and `name` (verify against `IslandBlueprint.get`; if `name` is absent, use `%parameter_blueprint_id%` in the display name).

- [ ] **Step 1: Confirm blueprint placeholder fields**

Run: `sed -n '1,60p' src/main/java/com/astralrealms/skyblock/model/IslandBlueprint.java`
Expected: note which keys the `get(PlaceholderContext)` switch returns (e.g. `id`, `name`, `icon`). Use only those keys in Step 2; replace `%parameter_blueprint_name%` / the `copy-from` icon with the actual exposed keys if they differ.

- [ ] **Step 2: Create `creation.yml`**

```yaml
id: "island-creation"
title: "%img_internal:neg_8%<white>%img_guis:generic_list%%img_internal:neg_98%<font:alphabet:shift_1>creation"
size: 54
use-player-inventory: true

layouts:
  blueprints:
    taint: "blueprint"
    provider: "%parameters_blueprints%"

items:
  blueprint:
    slots: [10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34]
    material: "GRASS_BLOCK"
    name: "<primary_color><bold>%parameter_blueprint_name%"
    lore:
      - ""
      - "  <text_color>Crée une nouvelle <secondary_color>île"
      - "  <text_color>à partir de ce <secondary_color>modèle<text_color>."
      - ""
      - "<white>%img_icons:left-click% <information_color>Créer cette île"
    taints:
      - "blueprint"
    actions:
      - "[close]"
      - "[create-island] %parameter_blueprint_id%"
```

- [ ] **Step 3: Validate parse**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" src/main/resources/menus/island/creation.yml`
Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/menus/island/creation.yml
git commit -m "feat(menus): add island creation blueprint picker menu"
```

---

### Task 7: Confirm dialogs

Four check/cross confirmation menus following AstralTown's `validation_delete.yml` pattern.

**Files:**
- Create: `src/main/resources/menus/island/confirm/confirm-kick.yml`
- Create: `src/main/resources/menus/island/confirm/confirm-ban.yml`
- Create: `src/main/resources/menus/island/confirm/confirm-leave.yml`
- Create: `src/main/resources/menus/island/confirm/confirm-disband.yml`

**Interfaces:**
- Consumes: `member-manage` opens `island-confirm-kick` and `island-confirm-ban` (both with `island` + `member`). `island-confirm-leave` and `island-confirm-disband` are opened from commands with `island`.
- Produces: confirm → respective action (`[kick-member]`, `[ban-member]`, `[leave-island]`, `[disband-island]`); cancel → back to origin menu.

- [ ] **Step 1: Create `confirm/confirm-kick.yml`**

```yaml
id: "island-confirm-kick"
title: "%img_internal:neg_8%<white>%img_guis:generic_27%%img_internal:neg_176%%img_title%%img_internal:neg_118%<font:alphabet:alphabet>validation"
size: 54
use-player-inventory: true

items:
  confirm:
    slot: 14
    copy-from: "%stacksuppliers_ce_icons:check%"
    name: "<bold><primary_color>Expulser le membre"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[kick-member] %parameters_island_id% %parameters_member_playerId%"
      - "[open-menu] island-members:island=%parameters_island%"

  cancel:
    slot: 12
    copy-from: "%stacksuppliers_ce_icons:cross%"
    name: "<bold><primary_color>Annuler"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-member-manage:island=%parameters_island%:member=%parameters_member%"
```

- [ ] **Step 2: Create `confirm/confirm-ban.yml`**

```yaml
id: "island-confirm-ban"
title: "%img_internal:neg_8%<white>%img_guis:generic_27%%img_internal:neg_176%%img_title%%img_internal:neg_118%<font:alphabet:alphabet>validation"
size: 54
use-player-inventory: true

items:
  confirm:
    slot: 14
    copy-from: "%stacksuppliers_ce_icons:check%"
    name: "<bold><primary_color>Bannir le membre"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[ban-member] %parameters_island_id% %parameters_member_playerId%"
      - "[open-menu] island-members:island=%parameters_island%"

  cancel:
    slot: 12
    copy-from: "%stacksuppliers_ce_icons:cross%"
    name: "<bold><primary_color>Annuler"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-member-manage:island=%parameters_island%:member=%parameters_member%"
```

- [ ] **Step 3: Create `confirm/confirm-leave.yml`**

```yaml
id: "island-confirm-leave"
title: "%img_internal:neg_8%<white>%img_guis:generic_27%%img_internal:neg_176%%img_title%%img_internal:neg_118%<font:alphabet:alphabet>validation"
size: 54
use-player-inventory: true

items:
  confirm:
    slot: 14
    copy-from: "%stacksuppliers_ce_icons:check%"
    name: "<bold><red>Quitter l'île"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[leave-island] %parameters_island_id%"
      - "[close]"

  cancel:
    slot: 12
    copy-from: "%stacksuppliers_ce_icons:cross%"
    name: "<bold><primary_color>Annuler"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"
```

- [ ] **Step 4: Create `confirm/confirm-disband.yml`**

```yaml
id: "island-confirm-disband"
title: "%img_internal:neg_8%<white>%img_guis:generic_27%%img_internal:neg_176%%img_title%%img_internal:neg_118%<font:alphabet:alphabet>validation"
size: 54
use-player-inventory: true

items:
  confirm:
    slot: 14
    copy-from: "%stacksuppliers_ce_icons:check%"
    name: "<bold><red>Supprimer l'île"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[disband-island] %parameters_island_id%"
      - "[close]"

  cancel:
    slot: 12
    copy-from: "%stacksuppliers_ce_icons:cross%"
    name: "<bold><primary_color>Annuler"
    item-flags:
      - HIDE_ATTRIBUTES
    actions:
      - "[open-menu] island-main:island=%parameters_island%"
```

- [ ] **Step 5: Validate all four parse**

Run:
```bash
for f in confirm-kick confirm-ban confirm-leave confirm-disband; do python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "src/main/resources/menus/island/confirm/$f.yml" && echo "$f ok"; done
```
Expected: `confirm-kick ok` / `confirm-ban ok` / `confirm-leave ok` / `confirm-disband ok`.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/menus/island/confirm/
git commit -m "feat(menus): add confirm dialogs for kick, ban, leave, disband"
```

---

## Final verification

- [ ] **All 14 files parse:**

```bash
find src/main/resources/menus/island -name '*.yml' | while read f; do python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" "$f" || echo "FAIL: $f"; done; echo "count: $(find src/main/resources/menus/island -name '*.yml' | wc -l)"
```
Expected: no `FAIL` lines; `count: 14`.

- [ ] **Unique menu ids:**

```bash
grep -rh '^id:' src/main/resources/menus/island | sort | uniq -d
```
Expected: no output (every `id` is unique).

## Notes for the implementer

- **Do not** touch any `.java` file or `AstralSkyblock.java`. If a menu seems to need a behavior that doesn't exist, that's expected — it's the documented Java contract in the spec (`docs/superpowers/specs/2026-06-29-astralskyblock-menus-design.md`), not your job here.
- The only place to *read* Java is Task 6 Step 1 (confirm blueprint placeholder keys) — read-only, no edits.
- If `python3`/`pyyaml` is unavailable, any YAML linter works; the goal is only to confirm each file is syntactically valid YAML.
- Slot lists are written inline (`[1,2,3,...]`) for compactness; this is valid YAML flow syntax and parses identically to AstralTown's block lists.
