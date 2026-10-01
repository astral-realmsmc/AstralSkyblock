# AstralSkyblock

Network skyblock for Paper 1.21: every island is its own Advanced Slime Paper world, loaded on demand
on one server of an "islands" group and reachable from anywhere on the network.

## Requirements

- Paper 1.21 with **Advanced Slime Paper** (ASP 4.2)
- **AstralCore** (database, Redis cache, RabbitMQ messaging, menus, dialogs, economy and teleport services)
- MariaDB 10.11+ (native `UUID` type) for the plugin data, and a MySQL/MariaDB database for the slime worlds
- Redis and RabbitMQ, shared by every server running the plugin

## Servers

Install the plugin on every server players use it from (hubs included). Servers whose AstralCore group
equals `islands-group` in `config.yml` host island worlds; the others only route players to them.

- An island is hosted by exactly one island server at a time, claimed in Redis
  (`skyblock:islands:server`), and unloaded after `world-idle-unload-seconds` without players.
- New islands, and islands nobody hosts, go to the island server with the fewest loaded islands that is
  under `maximum-islands`.
- Evicted players (bans, deleted islands) are sent to `fallback-group`.

## Setup

1. **Database.** Fill in `database.properties` (plugin data) and `loader.yml` (slime worlds). The
   plugin creates its tables from `schema.sql` on startup. There are no migrations: `schema.sql`
   always describes the latest tables, so a schema change means a fresh database (or a hand-applied
   `ALTER`).
2. **Source world.** Put the world every island is cloned from in `plugins/AstralSkyblock/sourceWorlds/`
   as a `.slime` file, and point a blueprint at it in `blueprints/` (`source-world`, plus the island
   spawn as `spawn-location`). One blueprint must be `default: true`; without it island creation is
   refused.
3. **Economy.** Upgrade prices are charged through AstralCore's economy service; `currency` in
   `upgrades/*.yml` names one of its currencies (blank uses the network default).
4. Restart. Configuration files are only copied into the data folder when missing, so after an update
   compare them with the bundled ones.

## Configuration

| File | Contents |
|---|---|
| `config.yml` | Servers, limits and defaults, level scoring, generators, allowed biomes, default island settings |
| `roles.yml` | The roles every new island starts with, their weights, icons and permissions |
| `permissions.yml` / `settings.yml` | Menu items for each island permission and setting |
| `block-values.yml` | Points per block for island levels (names, `#tags`, `*` patterns, `MOB_SPAWNER:<TYPE>`) |
| `generators/` | Cobblestone generator outputs and their weights |
| `upgrades/` | Upgrade levels, prices and effects |
| `blueprints/` | Island templates |
| `menus/`, `dialogs/` | AstralCore menus and dialogs |
| `messages.yml` | Every chat message |

## Commands

`/is` opens the island menu (or the creation menu). Main subcommands: `create`, `go`/`visit`, `delete`,
`rename`, `sethome`, `close`/`open`, `expel`, `biome`, `invite`, `coop`, `invites`, `accept`, `decline`,
`cancel`, `kick`, `leave`, `promote`, `demote`, `transfer`, `ban`, `unban`, `bans`, `uncoop`, `warp`,
`warps`, `setwarp`, `delwarp`, `calc`, `top`, `upgrades`. Staff: `/is upgrade set` and `/skyblock reload`.

Permissions: `skyblock.admin` bypasses island permissions and cooldowns; `skyblock.reload` allows the
reload command.

## API

`com.astralrealms.skyblock.SkyblockAPI` exposes read access to islands, membership, roles, permissions,
settings, warps and upgrades. Changes are announced through Bukkit events in
`com.astralrealms.skyblock.event`.

## Development

```
mvn package      # build
mvn test         # unit tests
```
