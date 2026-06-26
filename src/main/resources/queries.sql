-- =====================================================================================
--  AstralSkyblock — application query set (MariaDB)
-- -------------------------------------------------------------------------------------
--  Conventions
--   * `?` are JDBC positional params; each query lists them in order as [a, b, c].
--   * Hot paths (protection checks on every event) resolve permissions IN MEMORY from
--     the loaded island aggregate. The DB resolver at the bottom is for cold/cross-server
--     checks only — never call it per BlockBreakEvent.
--   * For uniqueness conflicts (island/warp/role names) prefer ATTEMPT + catch the
--     duplicate-key error over a pre-check SELECT — the latter is a TOCTOU race.
--   * Blocks marked "TX" are a single transaction; statement order is load-bearing
--     because the unique virtual columns and the owner<=>no-role CHECK are validated
--     per statement, not deferred.
-- =====================================================================================


-- =====================================================================================
--  PLAYERS
-- =====================================================================================

-- Upsert on join. last_seen is set explicitly so it refreshes even when the name is
-- unchanged (ON UPDATE CURRENT_TIMESTAMP only fires when a value actually changes).
-- [uuid, name]
INSERT INTO players (uuid, name)
VALUES (?, ?)
ON DUPLICATE KEY UPDATE name      = VALUES(name),
                        last_seen = CURRENT_TIMESTAMP(3);

-- Load by uuid.  [uuid]
SELECT uuid, name, first_seen, last_seen
FROM players
WHERE uuid = ?;

-- Resolve name -> uuid (case-insensitive via the column collation; uses idx_players_name).
-- LIMIT 1 guards the rare case of a name reused after a rename.  [name]
SELECT uuid, name
FROM players
WHERE name = ?
ORDER BY last_seen DESC
LIMIT 1;


-- =====================================================================================
--  ISLAND — CREATION  (TX: island -> roles -> seed perms -> owner member)
-- =====================================================================================

-- 1. Insert the island. id is an app-generated UUIDv7. name/biome default NULL,
--    locked=FALSE, level=0, timestamps auto. A duplicate-key on uq_islands_cell means
--    the chosen cell was taken concurrently — pick the next free cell and retry.
-- [id, world, center_x, center_z, spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch]
INSERT INTO islands (id, world, center_x, center_z,
                     spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- 2. Create the three baseline roles, one statement each so getGeneratedKeys() yields
--    each id reliably (do not rely on sequential ids from a multi-row insert).
-- [island_id]  (VISITOR, kind=1)
INSERT INTO island_roles (island_id, kind, name, weight, is_default)
VALUES (?, 1, 'Visitor', 0, FALSE);
-- [island_id]  (COOP, kind=2)
INSERT INTO island_roles (island_id, kind, name, weight, is_default)
VALUES (?, 2, 'Coop', 1, FALSE);
-- [island_id]  (default MEMBER, kind=0)
INSERT INTO island_roles (island_id, kind, name, weight, is_default)
VALUES (?, 0, 'Member', 10, TRUE);

-- 3. Seed each role's permissions from config (batch).  [role_id, permission] x N
INSERT INTO island_role_permissions (role_id, permission)
VALUES (?, ?);

-- 4. Owner member: structural, no role.  [island_id, owner_uuid]
INSERT INTO island_members (island_id, player_uuid, is_owner, role_id)
VALUES (?, ?, TRUE, NULL);


-- =====================================================================================
--  ISLAND — LOADS
-- =====================================================================================

-- Full island row by id.  [id]
SELECT id,
       name,
       world,
       center_x,
       center_z,
       biome,
       spawn_world,
       spawn_x,
       spawn_y,
       spawn_z,
       spawn_yaw,
       spawn_pitch,
       locked,
       level,
       created_at,
       updated_at
FROM islands
WHERE id = ?;

-- The island a player belongs to (single row; uses uq_member_player). One round trip.  [player_uuid]
SELECT i.id,
       i.name,
       i.world,
       i.center_x,
       i.center_z,
       i.biome,
       i.spawn_world,
       i.spawn_x,
       i.spawn_y,
       i.spawn_z,
       i.spawn_yaw,
       i.spawn_pitch,
       i.locked,
       i.level,
       i.created_at,
       i.updated_at
FROM island_members m
         JOIN islands i ON i.id = m.island_id
WHERE m.player_uuid = ?;

-- By name (case-insensitive; uses uq_islands_name).  [name]
SELECT id
FROM islands
WHERE name = ?;

-- By grid cell — the protection lookup. App floors the player's block coords to the cell
-- centre, then this is a unique-index hit on uq_islands_cell.  [world, center_x, center_z]
SELECT id
FROM islands
WHERE world = ?
  AND center_x = ?
  AND center_z = ?;

-- Owner of an island via the unique virtual column owner_guard (uq_single_owner).  [island_id]
SELECT player_uuid
FROM island_members
WHERE owner_guard = ?;

-- /is info summary: owner name + member count in one shot.  [island_id]
SELECT i.id,
       i.name,
       i.level,
       i.locked,
       i.biome,
       p.name                                                             AS owner_name,
       (SELECT COUNT(*) FROM island_members mm WHERE mm.island_id = i.id) AS members
FROM islands i
         JOIN island_members m ON m.owner_guard = i.id
         JOIN players p ON p.uuid = m.player_uuid
WHERE i.id = ?;

-- --- aggregate sub-loads (each a clustered range scan on its island_id prefix) ---

-- Members (raw).  [island_id]
SELECT player_uuid, is_owner, role_id, joined_at
FROM island_members
WHERE island_id = ?;

-- Members with role + name, for the members GUI (LEFT JOIN: owner has NULL role_id).  [island_id]
SELECT m.player_uuid,
       p.name,
       m.is_owner,
       m.role_id,
       r.name   AS role_name,
       r.weight AS role_weight,
       m.joined_at
FROM island_members m
         JOIN players p ON p.uuid = m.player_uuid
         LEFT JOIN island_roles r ON r.id = m.role_id
WHERE m.island_id = ?
ORDER BY m.is_owner DESC, r.weight DESC, p.name;

-- Roles, senior first.  [island_id]
SELECT id, kind, name, weight, is_default
FROM island_roles
WHERE island_id = ?
ORDER BY weight DESC, id;

-- All role-permissions for the island in one query (cache them keyed by role_id).  [island_id]
SELECT rp.role_id, rp.permission
FROM island_role_permissions rp
         JOIN island_roles r ON r.id = rp.role_id
WHERE r.island_id = ?;

-- Flags / upgrades / warps / bans / coops.  [island_id] each
SELECT flag, allowed
FROM island_flags
WHERE island_id = ?;
SELECT upgrade, level
FROM island_upgrades
WHERE island_id = ?;
SELECT name,
       world,
       x,
       y,
       z,
       yaw,
       pitch,
       is_private
FROM island_warps
WHERE island_id = ?;
SELECT player_uuid, banned_by, reason, created_at
FROM island_bans
WHERE island_id = ?;
SELECT player_uuid, added_by, created_at
FROM island_coops
WHERE island_id = ?;


-- =====================================================================================
--  ISLAND — UPDATES
-- =====================================================================================

-- Rename / unname (NULL = unnamed). Duplicate-key on uq_islands_name => name taken.  [name, id]
UPDATE islands
SET name = ?
WHERE id = ?;

-- Set spawn.  [spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, id]
UPDATE islands
SET spawn_world = ?,
    spawn_x     = ?,
    spawn_y     = ?,
    spawn_z     = ?,
    spawn_yaw   = ?,
    spawn_pitch = ?
WHERE id = ?;

-- Set biome / lock state.  [biome, id] / [locked, id]
UPDATE islands
SET biome = ?
WHERE id = ?;
UPDATE islands
SET locked = ?
WHERE id = ?;

-- Set cached level (recalc job; use executeBatch for a bulk recalc).  [level, id]
UPDATE islands
SET level = ?
WHERE id = ?;


-- =====================================================================================
--  ISLAND — LEADERBOARD  (/is top)
-- =====================================================================================

-- Top N by level, with owner name (owner join via uq_single_owner).  [limit, offset]
SELECT i.id, i.name, i.level, p.name AS owner_name
FROM islands i
         JOIN island_members m ON m.owner_guard = i.id
         JOIN players p ON p.uuid = m.player_uuid
ORDER BY i.level DESC
LIMIT ? OFFSET ?;

-- An island's 1-based rank (sargable on idx_islands_level).  [level]
SELECT 1 + COUNT(*) AS rank
FROM islands
WHERE level > ?;


-- =====================================================================================
--  ROLES — MANAGEMENT
-- =====================================================================================

-- Create a custom member role (RETURN_GENERATED_KEYS for the id). Duplicate-key on
-- uq_role_name => name taken on this island.  [island_id, name, weight]
INSERT INTO island_roles (island_id, kind, name, weight, is_default)
VALUES (?, 0, ?, ?, FALSE);

-- Load one role.  [id]
SELECT id, island_id, kind, name, weight, is_default
FROM island_roles
WHERE id = ?;

-- Default member-role id (unique lookup via uq_role_default; pass island_id).  [island_id]
SELECT id
FROM island_roles
WHERE default_guard = ?;

-- A system role id (unique lookup via uq_role_system; sys_kind 1=VISITOR, 2=COOP).  [island_id, sys_kind]
SELECT id
FROM island_roles
WHERE island_id = ?
  AND sys_kind = ?;

-- Rename / re-weight.  [name, id] / [weight, id]
UPDATE island_roles
SET name = ?
WHERE id = ?;
UPDATE island_roles
SET weight = ?
WHERE id = ?;

-- Switch the default role (TX). Clear first — uq_role_default forbids two defaults
-- existing at once. new_role must be kind=0 (chk_role_default).
-- [island_id]
UPDATE island_roles
SET is_default = FALSE
WHERE default_guard = ?;
-- [new_role_id]
UPDATE island_roles
SET is_default = TRUE
WHERE id = ?;

-- Delete a role (TX). App must verify it is neither default nor a system role, then
-- reassign its members to a target role, then delete. The members.role_id RESTRICT FK is
-- the backstop: the DELETE fails if any holder remains, and role_permissions CASCADE.
-- [target_role_id, island_id, doomed_role_id]
UPDATE island_members
SET role_id = ?
WHERE island_id = ?
  AND role_id = ?;
-- [doomed_role_id]
DELETE
FROM island_roles
WHERE id = ?;

-- Count holders of a role (e.g. before delete / for UI).  [island_id, role_id]
SELECT COUNT(*)
FROM island_members
WHERE island_id = ?
  AND role_id = ?;


-- =====================================================================================
--  ROLE PERMISSIONS
-- =====================================================================================

-- Grant (idempotent) / revoke a single permission.  [role_id, permission] each
INSERT IGNORE INTO island_role_permissions (role_id, permission)
VALUES (?, ?);
DELETE
FROM island_role_permissions
WHERE role_id = ?
  AND permission = ?;

-- Replace a role's entire grant set on GUI save (TX: wipe then batch insert).
-- [role_id]
DELETE
FROM island_role_permissions
WHERE role_id = ?;
-- [role_id, permission] x N
INSERT INTO island_role_permissions (role_id, permission)
VALUES (?, ?);

-- Point check (prefer in-memory).  [role_id, permission]
SELECT 1
FROM island_role_permissions
WHERE role_id = ?
  AND permission = ?
LIMIT 1;


-- =====================================================================================
--  MEMBERS
-- =====================================================================================

-- Add a member with the default role. Duplicate-key on uq_member_player => the player is
-- already on an island.  [island_id, player_uuid, default_role_id]
INSERT INTO island_members (island_id, player_uuid, is_owner, role_id)
VALUES (?, ?, FALSE, ?);

-- Remove (leave / kick). is_owner guard means this can never delete the owner.  [island_id, player_uuid]
DELETE
FROM island_members
WHERE island_id = ?
  AND player_uuid = ?
  AND is_owner = FALSE;

-- Set a member's role (promote / demote). Owner is excluded by the guard (and the CHECK
-- would reject a non-NULL role on the owner anyway).  [role_id, island_id, player_uuid]
UPDATE island_members
SET role_id = ?
WHERE island_id = ?
  AND player_uuid = ?
  AND is_owner = FALSE;

-- Get a member row (membership + role).  [island_id, player_uuid]
SELECT is_owner, role_id
FROM island_members
WHERE island_id = ?
  AND player_uuid = ?;

-- Member count (member-limit enforcement).  [island_id]
SELECT COUNT(*)
FROM island_members
WHERE island_id = ?;

-- Effective weight for an escalation check (NULL weight + is_owner=TRUE => above all).  [island_id, player_uuid]
SELECT m.is_owner, r.weight
FROM island_members m
         LEFT JOIN island_roles r ON r.id = m.role_id
WHERE m.island_id = ?
  AND m.player_uuid = ?;

-- Transfer ownership (TX). New owner must already be a member of this island.
-- Step 1 demotes the old owner INTO a role (frees owner_guard, satisfies the CHECK in the
-- same statement). Step 2 promotes the new owner (now the only one) and nulls their role.
-- Order is mandatory: uq_single_owner rejects two owners mid-flight.
-- [ex_owner_new_role_id, island_id, old_owner_uuid]
UPDATE island_members
SET is_owner = FALSE,
    role_id  = ?
WHERE island_id = ?
  AND player_uuid = ?
  AND is_owner = TRUE;
-- [island_id, new_owner_uuid]
UPDATE island_members
SET is_owner = TRUE,
    role_id  = NULL
WHERE island_id = ?
  AND player_uuid = ?;


-- =====================================================================================
--  BANS
-- =====================================================================================

-- Ban (re-ban updates reason/actor/time).  [island_id, player_uuid, banned_by, reason]
INSERT INTO island_bans (island_id, player_uuid, banned_by, reason)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE banned_by  = VALUES(banned_by),
                        reason     = VALUES(reason),
                        created_at = CURRENT_TIMESTAMP(3);

-- Unban.  [island_id, player_uuid]
DELETE
FROM island_bans
WHERE island_id = ?
  AND player_uuid = ?;

-- Is banned — entry/protection check (PK hit).  [island_id, player_uuid]
SELECT 1
FROM island_bans
WHERE island_id = ?
  AND player_uuid = ?
LIMIT 1;

-- Ban list with names.  [island_id]
SELECT b.player_uuid, p.name, b.banned_by, b.reason, b.created_at
FROM island_bans b
         JOIN players p ON p.uuid = b.player_uuid
WHERE b.island_id = ?
ORDER BY b.created_at DESC;


-- =====================================================================================
--  COOPS  (temporary access; resolves to the island's COOP role)
-- =====================================================================================

-- Add (refresh actor/time on repeat).  [island_id, player_uuid, added_by]
INSERT INTO island_coops (island_id, player_uuid, added_by)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE added_by   = VALUES(added_by),
                        created_at = CURRENT_TIMESTAMP(3);

-- Remove.  [island_id, player_uuid]
DELETE
FROM island_coops
WHERE island_id = ?
  AND player_uuid = ?;

-- Is coop (PK hit).  [island_id, player_uuid]
SELECT 1
FROM island_coops
WHERE island_id = ?
  AND player_uuid = ?
LIMIT 1;

-- List coops with names.  [island_id]
SELECT c.player_uuid, p.name, c.added_by, c.created_at
FROM island_coops c
         JOIN players p ON p.uuid = c.player_uuid
WHERE c.island_id = ?
ORDER BY c.created_at DESC;

-- A player's coop grants / clear them all (idx_coops_player). Useful if coop is
-- session-scoped.  [player_uuid] each
SELECT island_id
FROM island_coops
WHERE player_uuid = ?;
DELETE
FROM island_coops
WHERE player_uuid = ?;


-- =====================================================================================
--  WARPS
-- =====================================================================================

-- Create / move a warp (upsert on (island_id, name)).
-- [island_id, name, world, x, y, z, yaw, pitch, is_private]
INSERT INTO island_warps (island_id, name, world, x, y, z, yaw, pitch, is_private)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE world      = VALUES(world),
                        x          = VALUES(x),
                        y          = VALUES(y),
                        z          = VALUES(z),
                        yaw        = VALUES(yaw),
                        pitch      = VALUES(pitch),
                        is_private = VALUES(is_private);

-- Delete / fetch one.  [island_id, name] each
DELETE
FROM island_warps
WHERE island_id = ?
  AND name = ?;
SELECT world, x, y, z, yaw, pitch, is_private
FROM island_warps
WHERE island_id = ?
  AND name = ?;

-- Public list (visitors) / full list (members).  [island_id] each
SELECT name, world, x, y, z, yaw, pitch
FROM island_warps
WHERE island_id = ?
  AND is_private = FALSE
ORDER BY name;
SELECT name,
       world,
       x,
       y,
       z,
       yaw,
       pitch,
       is_private
FROM island_warps
WHERE island_id = ?
ORDER BY name;


-- =====================================================================================
--  FLAGS  (island-wide toggles; override-only)
-- =====================================================================================

-- Set override / revert to config default.  [island_id, flag, allowed] / [island_id, flag]
INSERT INTO island_flags (island_id, flag, allowed)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE allowed = VALUES(allowed);
DELETE
FROM island_flags
WHERE island_id = ?
  AND flag = ?;


-- =====================================================================================
--  UPGRADES  (rankup source of truth; override-only, absent = level 0)
-- =====================================================================================

-- Set an exact level.  [island_id, upgrade, level]
INSERT INTO island_upgrades (island_id, upgrade, level)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE level = VALUES(level);

-- Atomic rankup with a max cap (no read-modify-write).  [island_id, upgrade, max_level]
INSERT INTO island_upgrades (island_id, upgrade, level)
VALUES (?, ?, 1)
ON DUPLICATE KEY UPDATE level = LEAST(level + 1, ?);

-- One level (no row => treat as 0).  [island_id, upgrade]
SELECT level
FROM island_upgrades
WHERE island_id = ?
  AND upgrade = ?;


-- =====================================================================================
--  DISBAND  (TX: members first, then the island; the rest cascades)
-- -------------------------------------------------------------------------------------
--  Members must go before the island because members.role_id -> island_roles is RESTRICT,
--  while roles CASCADE from the island. Deleting members first removes those references so
--  the island delete can cascade roles (and, chained, role_permissions) cleanly.
--  Emit the bank-account closure to the economy plugin separately — no SQL here.
-- =====================================================================================

-- [island_id]
DELETE
FROM island_members
WHERE island_id = ?;
-- [island_id]
DELETE
FROM islands
WHERE id = ?;


-- =====================================================================================
--  COLD PERMISSION RESOLVER  (single round trip; NOT for hot paths)
-- -------------------------------------------------------------------------------------
--  Returns one boolean `allowed`. Owner short-circuits via OR. Otherwise the effective
--  role is: member's role, else (if coop) the COOP role, else the VISITOR role — and we
--  test whether that role grants the permission.
--  Params in order:
--    [island_id, player_uuid,         -- is_owner check
--     permission,                     -- permission being tested
--     island_id, player_uuid,         -- member's role_id
--     island_id, island_id, player_uuid,  -- COOP role (only if player is coop)
--     island_id]                      -- VISITOR role fallback
-- =====================================================================================
SELECT COALESCE((SELECT m.is_owner
                 FROM island_members m
                 WHERE m.island_id = ?
                   AND m.player_uuid = ?), FALSE)
           OR EXISTS (SELECT 1
                      FROM island_role_permissions rp
                      WHERE rp.permission = ?
                        AND rp.role_id = COALESCE(
                              (SELECT m.role_id
                               FROM island_members m
                               WHERE m.island_id = ?
                                 AND m.player_uuid = ?),
                              (SELECT r.id
                               FROM island_roles r
                               WHERE r.island_id = ?
                                 AND r.sys_kind = 2
                                 AND EXISTS (SELECT 1
                                             FROM island_coops c
                                             WHERE c.island_id = ?
                                               AND c.player_uuid = ?)),
                              (SELECT id FROM island_roles WHERE island_id = ? AND sys_kind = 1)
                                         )) AS allowed;