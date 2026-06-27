-- =====================================================================================
--  AstralSkyblock — baseline schema (V1)
-- -------------------------------------------------------------------------------------
--  Target:   MariaDB 10.11+ (native UUID type, CHECK, virtual generated columns)
--  Engine:   InnoDB, utf8mb4 / utf8mb4_unicode_ci throughout
--  Drop-in:  rename to V1__init_astralskyblock.sql for Flyway/Liquibase baseline
--
--  Design notes
--  ------------
--  * Money is NOT stored here. An island's bank is an account in the network economy
--    plugin (account_type = BANK), keyed by `islands.id`. This schema never holds a
--    balance — overdraft/idempotency/double-entry stay in the one place they're solved.
--  * `islands.id` is an app-generated UUIDv7 (insert locality in the clustered index,
--    unlike random v4) and doubles as the economy bank-account id and the cross-server
--    island identity.
--  * Flags / upgrades are OVERRIDE-ONLY: a row exists only when an island differs from
--    the configured default. Default islands carry almost no sub-rows.
--  * Invites are ephemeral and live in Redis with a TTL (cross-server, self-expiring),
--    so there is deliberately no invites table.
--  * MySQL 8 port: swap `UUID` -> `BINARY(16)`, keep everything else.
--
--  Roles & permissions (fully owner-customisable)
--  ----------------------------------------------
--  Every island defines its own roles in `island_roles`:
--    * custom MEMBER roles (kind=0): create / rename / delete / re-weight freely;
--      exactly one is flagged is_default (the role new members receive).
--    * one system VISITOR role (kind=1) and one COOP role (kind=2) per island:
--      undeletable, but their permissions are editable like any other role.
--  A role's granted permissions are the rows in `island_role_permissions`
--  (presence = grant, default-deny). `island_members.role_id` points at the holder's
--  role.
--
--  The OWNER is structural, NOT a role: is_owner = TRUE, role_id = NULL, and bypasses
--  every permission check — it cannot be deleted, demoted, or locked out by construction.
--
--  Hierarchy: `island_roles.weight`, higher = more senior. A member may manage only
--  roles/members of STRICTLY-lower weight, and only while holding the relevant permission
--  (e.g. MANAGE_ROLES / MANAGE_MEMBERS / SET_ROLE). The owner is above every role;
--  VISITOR/COOP sit at the bottom.
--
--  Effective permission resolution for a player on an island:
--    owner          -> allow all
--    member         -> grants of island_members.role_id
--    coop           -> grants of the island's COOP role
--    everyone else  -> grants of the island's VISITOR role
--  Environmental toggles (PVP, MOB_SPAWNING, EXPLOSIONS, ...) are NOT role-based;
--  they live in `island_flags`.
--
--  Operational contracts (app-side transactions; the data layer guarantees the
--  "at most one" parts via unique virtual columns):
--    Create island : insert island + VISITOR + COOP + >=1 default MEMBER role
--                    + owner member, seed role perms — all in one transaction.
--    Delete a role : reassign its members to another role first (members.role_id FK is
--                    RESTRICT), then delete the role (its perm rows CASCADE).
--    Disband       : delete island_members first, then DELETE the island; everything
--                    else (roles, perms, bans, coops, warps, flags, upgrades) cascades.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  players — canonical UUID <-> name record; FK target for every player reference.
--  (Having real referential integrity here prevents the "unrecognized uuid on load"
--   class of bug. Upsert on first contact.)
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS players
(
    uuid       UUID         NOT NULL,
    name       VARCHAR(32)  NOT NULL, -- last-known name (32 covers Floodgate-prefixed Bedrock names)
    first_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (uuid),
    KEY idx_players_name (name)       -- offline name -> uuid (case-insensitive via collation)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  islands — core identity, placement and spawn. No money, no member-count/size columns
--  (those are derived from island_upgrades + config and cached in the in-memory Island).
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS islands
(
    id          UUID         NOT NULL,               -- app-supplied UUIDv7; also the economy bank-account id
    name        VARCHAR(64)  NULL,                   -- unique when set (case-insensitive); NULL = unnamed
    world       VARCHAR(48)  NOT NULL,               -- overworld skyblock world; nether/end derive from the same cell

    spawn_x     DOUBLE       NOT NULL,
    spawn_y     DOUBLE       NOT NULL,
    spawn_z     DOUBLE       NOT NULL,
    spawn_yaw   FLOAT        NOT NULL DEFAULT 0,
    spawn_pitch FLOAT        NOT NULL DEFAULT 0,

    locked      BOOLEAN      NOT NULL DEFAULT FALSE, -- locked = visitors cannot enter
    level       BIGINT       NOT NULL DEFAULT 0,     -- cached rank metric (recalc job); /is top orders by this

    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_islands_name (name),               -- named islands are unique (NULLs exempt)
    KEY idx_islands_level (level)                    -- /is top leaderboard
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_roles — per-island, owner-defined roles. See header for the full model.
--    kind:       0=MEMBER (custom)  1=VISITOR (system)  2=COOP (system)
--    weight:     higher = more senior; management is allowed only on strictly-lower weights
--    is_default: the member role auto-assigned to new joiners (member-kind only)
--  Data-layer guarantees (the "exactly one" parts), via unique virtual columns:
--    * one VISITOR and one COOP role per island   -> uq_role_system
--    * one default member role per island         -> uq_role_default
--  ("at least one of each" is an app invariant established at island creation.)
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_roles
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    island_id     UUID         NOT NULL,
    kind          TINYINT      NOT NULL DEFAULT 0,
    name          VARCHAR(32)  NOT NULL,
    weight        INT          NOT NULL DEFAULT 0,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- one VISITOR + one COOP per island (member rows are NULL here, hence exempt):
    sys_kind      TINYINT AS (CASE WHEN kind IN (1, 2) THEN kind END) VIRTUAL,
    -- one default member-role per island:
    default_guard UUID AS (CASE WHEN is_default THEN island_id END) VIRTUAL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_role_name (island_id, name),                          -- role names unique per island (case-insensitive)
    UNIQUE KEY uq_role_system (island_id, sys_kind),                    -- one visitor + one coop per island
    UNIQUE KEY uq_role_default (default_guard),                         -- one default member role per island
    KEY idx_roles_island (island_id, weight),                           -- list an island's roles by seniority
    CONSTRAINT chk_role_kind CHECK (kind BETWEEN 0 AND 2),
    CONSTRAINT chk_role_default CHECK (is_default = FALSE OR kind = 0), -- only member roles can be default
    CONSTRAINT chk_role_weight CHECK (weight >= 0),
    CONSTRAINT fk_role_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_role_permissions — the grant set of each role. Presence = granted; default-deny.
--  Clustered by role_id, so a role's full permission set loads as one range scan.
--  Permission keys are config-driven strings (BUILD, BREAK, OPEN_CONTAINER, USE_REDSTONE,
--  INVITE, KICK, BAN, SET_ROLE, MANAGE_ROLES, MANAGE_MEMBERS, WITHDRAW, ...). A wildcard
--  key (e.g. '*') can be treated as all-grant in the app if desired — no schema change.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_role_permissions
(
    role_id    BIGINT      NOT NULL,
    permission VARCHAR(48) NOT NULL,

    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_roleperm_role FOREIGN KEY (role_id) REFERENCES island_roles (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_members — permanent membership. The OWNER is structural (is_owner = TRUE,
--  role_id = NULL, bypasses permission checks); everyone else holds a role.
--  Data-layer invariants:
--    (1) one island per player        -> uq_member_player
--    (2) exactly one owner per island -> uq_single_owner (virtual column trick;
--        MariaDB has no partial indexes)
--    (3) owner <=> no role            -> chk_member_owner_role
--  PK (island_id, player_uuid) clusters an island's member set for single-scan loads.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_members
(
    island_id   UUID         NOT NULL,
    player_uuid UUID         NOT NULL,
    is_owner    BOOLEAN      NOT NULL DEFAULT FALSE,
    role_id     BIGINT       NULL,             -- FK island_roles; NULL iff is_owner
    joined_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    owner_guard UUID AS (CASE WHEN is_owner THEN island_id END) VIRTUAL,

    PRIMARY KEY (island_id, player_uuid),
    UNIQUE KEY uq_member_player (player_uuid), -- a player belongs to at most one island
    UNIQUE KEY uq_single_owner (owner_guard),  -- at most one OWNER per island
    KEY idx_members_role (role_id),            -- FK index + "reassign all holders of role X"
    CONSTRAINT chk_member_owner_role CHECK (is_owner = (role_id IS NULL)),
    CONSTRAINT fk_member_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_player FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE RESTRICT,
    CONSTRAINT fk_member_role FOREIGN KEY (role_id) REFERENCES island_roles (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
-- NOTE: members' role_id should reference a kind=0 (MEMBER) role — that cross-row check
-- is enforced in application code, not by the FK. "At least one owner" is likewise an app
-- invariant (owner row created with the island; island is disbanded rather than left
-- owner-less). RESTRICT on player_uuid blocks purging a player who still owns/joins an
-- island; RESTRICT on role_id blocks deleting a role still in use (reassign first).

-- -------------------------------------------------------------------------------------
--  island_bans — "is player X banned from island Y" is a single PK lookup.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_bans
(
    island_id   UUID         NOT NULL,
    player_uuid UUID         NOT NULL,
    banned_by   UUID         NULL, -- audit only, intentionally no FK
    reason      VARCHAR(255) NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (island_id, player_uuid),
    CONSTRAINT fk_ban_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE,
    CONSTRAINT fk_ban_player FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_coops — temporary access; a coop player keeps their own membership elsewhere,
--  so this is a separate many-to-many (no UNIQUE on player_uuid). Coop players resolve
--  to the island's COOP role for permissions. Make ephemeral (Redis) later if you prefer
--  coop to clear on owner-offline / restart.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_coops
(
    island_id   UUID         NOT NULL,
    player_uuid UUID         NOT NULL,
    added_by    UUID         NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (island_id, player_uuid),
    KEY idx_coops_player (player_uuid), -- list / clear a player's coop grants
    CONSTRAINT fk_coop_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE,
    CONSTRAINT fk_coop_player FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_warps — additional named warps. Per-island name uniqueness via the PK; the
--  island's primary teleport point lives inline on `islands` (spawn_*).
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_warps
(
    island_id  UUID         NOT NULL,
    name       VARCHAR(48)  NOT NULL, -- case-insensitive per island via collation
    x          DOUBLE       NOT NULL,
    y          DOUBLE       NOT NULL,
    z          DOUBLE       NOT NULL,
    yaw        FLOAT        NOT NULL DEFAULT 0,
    pitch      FLOAT        NOT NULL DEFAULT 0,
    is_private BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (island_id, name),
    CONSTRAINT fk_warp_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_flags — island-wide boolean settings (PVP, MOB_SPAWNING, EXPLOSIONS,
--  FIRE_SPREAD, ...). NOT role-based. Override-only: a row exists only where the island
--  differs from the configured default. Valid flag keys are config-driven (not DB-enforced).
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_flags
(
    island_id UUID        NOT NULL,
    flag      VARCHAR(48) NOT NULL,
    allowed   BOOLEAN     NOT NULL,

    PRIMARY KEY (island_id, flag),
    CONSTRAINT fk_flag_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------------------
--  island_upgrades — rankup source of truth (size, member_limit, coop_limit, generator,
--  spawner_rate, ...). The *effect* of a level (actual border radius, member cap,
--  multiplier) is resolved from config; nothing derived is stored. Override-only:
--  absent upgrade = level 0.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS island_upgrades
(
    island_id UUID        NOT NULL,
    upgrade   VARCHAR(48) NOT NULL,
    level     INT         NOT NULL DEFAULT 0,

    PRIMARY KEY (island_id, upgrade),
    CONSTRAINT chk_upgrade_level CHECK (level >= 0),
    CONSTRAINT fk_upgrade_island FOREIGN KEY (island_id) REFERENCES islands (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;