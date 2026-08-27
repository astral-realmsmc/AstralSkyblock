package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.astralrealms.core.model.location.NetworkLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.repository.WarpRepository;
import com.astralrealms.skyblock.utils.PlayerText;

/**
 * Island warps: additional named teleport points on top of the island's inline spawn.
 *
 * <p>Warps are fully customisable — the icon shown in the warp menu is the material of the item the
 * player is holding when they set it, and both the display name and the description are free
 * MiniMessage text. Private warps are visible and usable only to the island's members and coops.
 *
 * <p>Teleporting resolves through {@link IslandService#resolveLocation}, so warping works from any
 * server in the group: the island world is loaded wherever there is room and the player is sent
 * there.
 */
public class WarpService {

    /** Warp names are used as identifiers in commands and menus, so keep them simple. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final AstralSkyblock plugin;
    private final WarpRepository repository;

    public WarpService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new WarpRepository(plugin);
    }

    public WarpRepository repository() {
        return this.repository;
    }

    // =========================================================================
    //  Write operations
    // =========================================================================

    /**
     * Creates a warp at the player's current position. Requires {@link IslandPermission#SET_WARP},
     * a valid and unused name, a position inside the island's own world, and room under the
     * configured warp limit. The icon defaults to the item in the player's main hand.
     */
    public CompletableFuture<Void> create(Island island, Player player, String name) {
        PlaceholderContainer placeholders = placeholders(player, island).registerDirect("warp_name", name);

        if (!island.hasPermission(player, IslandPermission.SET_WARP)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }
        if (!isValidName(name)) {
            ASMessages.WARP_INVALID_NAME.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (!isInIslandWorld(player, island)) {
            ASMessages.WARP_NOT_ON_ISLAND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (island.findWarp(name).isPresent()) {
            ASMessages.WARP_ALREADY_EXISTS.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        int maximum = this.plugin.configuration().maximumWarps();
        if (island.warps().size() >= maximum) {
            ASMessages.WARP_LIMIT_REACHED.message(player, placeholders.registerDirect("warp_limit", maximum));
            return CompletableFuture.completedFuture(null);
        }

        Location location = player.getLocation();
        IslandWarp warp = new IslandWarp(
                island.uniqueId(), name,
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                false,
                heldMaterial(player).orElse(IslandWarp.DEFAULT_ICON),
                null, null,
                System.currentTimeMillis()
        );

        return persist(island, player, warp, placeholders, ASMessages.WARP_CREATED);
    }

    /**
     * Deletes a warp. Requires {@link IslandPermission#DELETE_WARP}.
     */
    public CompletableFuture<Void> delete(Island island, Player player, String name) {
        PlaceholderContainer placeholders = placeholders(player, island).registerDirect("warp_name", name);

        if (!island.hasPermission(player, IslandPermission.DELETE_WARP)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }
        IslandWarp warp = island.findWarp(name).orElse(null);
        if (warp == null) {
            ASMessages.WARP_NOT_FOUND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        placeholders.registerPlaceholder(warp);
        return repository.remove(island.uniqueId(), warp.name())
                .thenCompose(ignored -> this.plugin.islands().refreshWarps(island.uniqueId()))
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to delete warp {} of island {}", name, island.uniqueId(), throwable);
                        return null;
                    }
                    ASMessages.WARP_DELETED.message(player, placeholders);
                    return null;
                });
    }

    /**
     * Sets a warp's icon to the material of the item in the player's main hand. Requires
     * {@link IslandPermission#SET_WARP} and a non-empty hand.
     */
    public CompletableFuture<Void> setIcon(Island island, Player player, String name) {
        return edit(island, player, name, ASMessages.WARP_ICON_UPDATED, (warp, placeholders) -> {
            String material = heldMaterial(player).orElse(null);
            if (material == null) {
                ASMessages.WARP_NO_ITEM_IN_HAND.message(player, placeholders);
                return false;
            }
            warp.icon(material);
            return true;
        });
    }

    /**
     * Sets a warp's display name — the title shown in the warp menu instead of its raw name.
     * A blank value clears it.
     */
    public CompletableFuture<Void> setDisplayName(Island island, Player player, String name, String displayName) {
        return edit(island, player, name, ASMessages.WARP_NAME_UPDATED, (warp, placeholders) -> {
            if (tooLong(player, placeholders, displayName, PlayerText.WARP_DISPLAY_NAME_LIMIT))
                return false;
            warp.displayName(PlayerText.sanitise(displayName));
            return true;
        });
    }

    /**
     * Sets a warp's description, rendered as lore in the warp menu. Lines are separated by
     * {@link IslandWarp#DESCRIPTION_SEPARATOR}; a blank value clears it.
     */
    public CompletableFuture<Void> setDescription(Island island, Player player, String name, String description) {
        return edit(island, player, name, ASMessages.WARP_DESCRIPTION_UPDATED, (warp, placeholders) -> {
            if (tooLong(player, placeholders, description, PlayerText.WARP_DESCRIPTION_LIMIT))
                return false;
            warp.description(PlayerText.sanitise(description));
            return true;
        });
    }

    /** Flips a warp between public and members-only. */
    public CompletableFuture<Void> togglePrivate(Island island, Player player, String name) {
        return edit(island, player, name, ASMessages.WARP_VISIBILITY_UPDATED, (warp, placeholders) -> {
            warp.isPrivate(!warp.isPrivate());
            return true;
        });
    }

    /** Moves a warp to the player's current position. */
    public CompletableFuture<Void> relocate(Island island, Player player, String name) {
        return edit(island, player, name, ASMessages.WARP_MOVED, (warp, placeholders) -> {
            if (!isInIslandWorld(player, island)) {
                ASMessages.WARP_NOT_ON_ISLAND.message(player, placeholders);
                return false;
            }
            warp.location(player.getLocation());
            return true;
        });
    }

    // =========================================================================
    //  Teleportation
    // =========================================================================

    /**
     * Teleports a player to one of an island's warps, loading the island world (here or on another
     * island server) when nothing hosts it yet. Refuses when the player is banned from the island,
     * when the island is closed to them, or when the warp is private and the player is an outsider.
     */
    public CompletableFuture<Void> teleport(Island island, Player player, String name) {
        PlaceholderContainer placeholders = placeholders(player, island).registerDirect("warp_name", name);

        IslandWarp warp = island.findWarp(name).orElse(null);
        if (warp == null) {
            ASMessages.WARP_NOT_FOUND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        placeholders.registerPlaceholder(warp);

        if (this.plugin.bans().isBanned(island.uniqueId(), player.getUniqueId())) {
            ASMessages.BANNED_FROM_ISLAND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        // Caught by IslandListener on arrival too, but refusing here spares the network the cost of
        // loading an island world the player is only going to be bounced out of.
        if (island.locked() && !this.plugin.islands().mayEnterClosed(island, player)) {
            ASMessages.ISLAND_IS_CLOSED.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (warp.isPrivate() && !island.isInsider(player)) {
            ASMessages.WARP_PRIVATE.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return this.plugin.islands()
                .resolveLocation(island, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch())
                .handle((location, throwable) -> {
                    if (throwable != null || location == null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to resolve warp {} of island {}", name, island.uniqueId(), throwable);
                        return null;
                    }

                    teleportTo(player, location, placeholders);
                    return null;
                });
    }

    private void teleportTo(Player player, NetworkLocation location, PlaceholderContainer placeholders) {
        AstralPaperAPI.getService(TeleportationService.class)
                .orElseThrow()
                .teleport(player.getUniqueId(), location)
                .exceptionally(throwable -> {
                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                    this.plugin.getSLF4JLogger().error("Failed to teleport {} to a warp", player.getName(), throwable);
                    return null;
                });
    }

    // =========================================================================
    //  Read operations
    // =========================================================================

    /** Every warp of an island; primes the island's slice from the database on first access. */
    public CompletableFuture<List<IslandWarp>> findByIsland(UUID islandId) {
        return repository.findByIsland(islandId);
    }

    /** A warp by name, read through the cache. */
    public CompletableFuture<Optional<IslandWarp>> findByName(UUID islandId, String name) {
        return repository.findByName(islandId, name);
    }

    /** Cached warp names of an island — used for command completion. */
    public List<String> names(UUID islandId) {
        return repository.findCachedByIsland(islandId).stream()
                .map(IslandWarp::name)
                .toList();
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Shared edit path: resolves the warp, checks {@link IslandPermission#SET_WARP}, applies the
     * mutation and persists it. The mutation returns {@code false} when it already messaged the
     * player about a failed precondition.
     */
    private CompletableFuture<Void> edit(Island island, Player player, String name,
                                         ASMessages success, WarpMutation mutation) {
        PlaceholderContainer placeholders = placeholders(player, island).registerDirect("warp_name", name);

        if (!island.hasPermission(player, IslandPermission.SET_WARP)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }
        IslandWarp warp = island.findWarp(name).orElse(null);
        if (warp == null) {
            ASMessages.WARP_NOT_FOUND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        placeholders.registerPlaceholder(warp);
        if (!mutation.apply(warp, placeholders))
            return CompletableFuture.completedFuture(null);

        return persist(island, player, warp, placeholders, success);
    }

    private CompletableFuture<Void> persist(Island island, Player player, IslandWarp warp,
                                            PlaceholderContainer placeholders, ASMessages success) {
        return repository.upsert(warp)
                .thenCompose(ignored -> this.plugin.islands().refreshWarps(island.uniqueId()))
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to save warp {} of island {}", warp.name(), island.uniqueId(), throwable);
                        return null;
                    }
                    success.message(player, placeholders);
                    return null;
                });
    }

    /**
     * Messages the player and reports {@code true} when their text exceeds what the column holds.
     * Sanitising escapes MiniMessage tags and can roughly double the length, which the raw limits in
     * {@link PlayerText} already account for.
     */
    private boolean tooLong(Player player, PlaceholderContainer placeholders, String input, int limit) {
        if (PlayerText.withinLimit(input, limit))
            return false;
        ASMessages.TEXT_TOO_LONG.message(player, placeholders.registerDirect("maximum", limit));
        return true;
    }

    /** Whether the player stands in the island's own world — warps may not point elsewhere. */
    private boolean isInIslandWorld(Player player, Island island) {
        return player.getWorld().getName().equals(island.uniqueId().toString());
    }

    private boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /** The material name of the item in the player's main hand, if they are holding anything. */
    private Optional<String> heldMaterial(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR)
            return Optional.empty();
        return Optional.of(item.getType().name().toUpperCase(Locale.ROOT));
    }

    private PlaceholderContainer placeholders(Player player, Island island) {
        return AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island);
    }

    @FunctionalInterface
    private interface WarpMutation {
        /** @return whether the mutation applied; {@code false} means the player was already told why not. */
        boolean apply(IslandWarp warp, PlaceholderContainer placeholders);
    }
}
