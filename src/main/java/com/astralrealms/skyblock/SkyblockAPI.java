package com.astralrealms.skyblock;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.astralrealms.core.model.location.NetworkLocation;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import lombok.experimental.UtilityClass;

/**
 * Entry point for other plugins that need to read skyblock state.
 *
 * <p>Every lookup here is served from the local cache unless its name says otherwise
 * ({@code load...} / methods returning a {@link CompletableFuture}), so it is safe to call from the
 * main thread — including inside event handlers.
 *
 * <p>An island is only resolvable by world on the server hosting it; on a lobby, use the
 * player-based and id-based lookups instead.
 */
@UtilityClass
public class SkyblockAPI {

    private static AstralSkyblock plugin;

    static void initialize(AstralSkyblock plugin) {
        SkyblockAPI.plugin = plugin;
    }

    // =========================================================================
    //  Islands
    // =========================================================================

    /** The island with this id, when it is cached on this server. */
    public static Optional<Island> findIslandByUniqueId(UUID uniqueId) {
        return plugin.islands()
                .repository()
                .findCachedById(uniqueId);
    }

    /**
     * The island with this id, loading it — with its members, roles, coops, bans, warps, settings
     * and upgrades — from the database when it is not cached here.
     */
    public static CompletableFuture<Island> loadIslandByUniqueId(UUID uniqueId) {
        return plugin.islands()
                .repository()
                .findById(uniqueId);
    }

    /** The island answering to this name, case-sensitively, when it is cached on this server. */
    public static Optional<Island> findIslandByName(String name) {
        return plugin.islands()
                .repository()
                .findByName(name);
    }

    /** The island this player is a member of — the owner counts as one. */
    public static Optional<Island> findIslandByPlayer(Player player) {
        return findIslandByPlayer(player.getUniqueId());
    }

    /** The island this player is a member of — the owner counts as one. */
    public static Optional<Island> findIslandByPlayer(UUID playerUuid) {
        return plugin.members().findPlayerIsland(playerUuid);
    }

    /** The island whose world is loaded here, if this location is on one. */
    public static Optional<Island> findIslandByLocation(Location location) {
        return findIslandByWorld(location.getWorld());
    }

    /** The island backed by this world, if this server hosts it. */
    public static Optional<Island> findIslandByWorld(World world) {
        return plugin.worlds().findByWorld(world);
    }

    /** Every island currently cached on this server. */
    public static Collection<Island> islands() {
        return plugin.islands().islands();
    }

    /** The island leaderboard as of the last refresh, best first. */
    public static List<Island> topIslands() {
        return plugin.levels().top();
    }

    /**
     * Resolves the island's home as a network location, loading its world here or on the emptiest
     * island server when nothing hosts it yet.
     */
    public static CompletableFuture<NetworkLocation> resolveIslandSpawn(Island island) {
        return plugin.islands().spawnIsland(island);
    }

    // =========================================================================
    //  Membership
    // =========================================================================

    /** Whether the player is a member of this island — the owner counts as one. */
    public static boolean isMember(UUID islandId, UUID playerUuid) {
        return findIslandByUniqueId(islandId)
                .flatMap(island -> island.findMember(playerUuid))
                .isPresent();
    }

    /** Whether the player is cooped on this island. */
    public static boolean isCoop(UUID islandId, UUID playerUuid) {
        return plugin.coops().isCoop(islandId, playerUuid);
    }

    /** Whether the player is banned from this island. */
    public static boolean isBanned(UUID islandId, UUID playerUuid) {
        return plugin.bans().isBanned(islandId, playerUuid);
    }

    /** Whether the player stands on their own island. */
    public static boolean isWithinOwnIsland(Player player) {
        return isWithinOwnIsland(player, player.getLocation());
    }

    /** Whether this location is on an island the player is a member of. */
    public static boolean isWithinOwnIsland(Player player, Location location) {
        return findIslandByLocation(location)
                .map(island -> island.findMember(player.getUniqueId()).isPresent())
                .orElse(false);
    }

    // =========================================================================
    //  Permissions & settings
    // =========================================================================

    /**
     * Whether the player may do {@code permission} where they stand. Off any island — a location
     * whose world is not an island world — the answer is {@code true}: this plugin has nothing to
     * say about it.
     */
    public static boolean hasPermission(Player player, IslandPermission permission) {
        return hasPermission(player, player.getLocation(), permission);
    }

    /**
     * Whether the player may do {@code permission} at this location. Off any island the answer is
     * {@code true} — see {@link #hasPermission(Player, IslandPermission)}.
     */
    public static boolean hasPermission(Player player, Location location, IslandPermission permission) {
        return findIslandByLocation(location)
                .map(island -> island.hasPermission(player, permission))
                .orElse(true);
    }

    /** Whether the player may do {@code permission} on this island, wherever they are. */
    public static boolean hasPermission(Player player, Island island, IslandPermission permission) {
        return island.hasPermission(player, permission);
    }

    /** Whether the setting is enabled on the island covering this location; {@code false} off any island. */
    public static boolean isSettingEnabled(Location location, IslandSettings settings) {
        return findIslandByLocation(location)
                .map(island -> island.isSettingEnabled(settings))
                .orElse(false);
    }

    // =========================================================================
    //  Warps & upgrades
    // =========================================================================

    /** The island's warps, from its cached snapshot. */
    public static Collection<IslandWarp> findWarpsByIsland(UUID islandId) {
        return findIslandByUniqueId(islandId)
                .map(Island::warps)
                .orElse(List.of());
    }

    /** The warps this player may see on the island: public ones, plus private ones when they belong to it. */
    public static Collection<IslandWarp> findVisibleWarps(Island island, Player player) {
        return island.visibleWarps(player);
    }

    /** The island's level for an upgrade; {@code 0} when it was never purchased. */
    public static int upgradeLevel(UUID islandId, UpgradeType type) {
        return plugin.upgrades().level(islandId, type);
    }

    /** The numeric value an upgrade grants this island, falling back when it is unset. */
    public static double upgradeValue(Island island, UpgradeType type, double fallback) {
        return plugin.upgrades().value(island, type, fallback);
    }

    /** Recomputes the island's level from its blocks. Only the server hosting it may run the scan. */
    public static CompletableFuture<Long> calculateLevel(Island island) {
        return plugin.levels().calculate(island);
    }
}
