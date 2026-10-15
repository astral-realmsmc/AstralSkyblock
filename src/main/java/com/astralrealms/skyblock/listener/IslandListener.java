package com.astralrealms.skyblock.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.island.world.IslandDimensionLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldUnloadedEvent;
import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.service.BlockLimitService;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;

import lombok.RequiredArgsConstructor;

/**
 * Island-wide enforcement that is not settings- or permission-specific: keeping banned players out
 * of island worlds and applying an island's world-state upgrades when its world comes up.
 */
@RequiredArgsConstructor
public class IslandListener implements Listener {

    private final AstralSkyblock plugin;

    /**
     * Applies the island's world-state upgrades (currently its border) as soon as its worlds are
     * loaded on this server, and counts their hoppers so the cap applies from the first placement.
     * Nothing is scanned: the stored level stands until a block changes or someone runs /is calc.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandWorldLoaded(IslandWorldLoadedEvent event) {
        this.plugin.upgrades().applyEffects(event.island());

        UUID islandId = event.island().uniqueId();
        try {
            Map<String, Map<Long, Integer>> census = new HashMap<>();
            for (SlimeWorldInstance instance : this.plugin.worlds().islandInstances(islandId))
                census.put(instance.getName(), BlockLimitService.census(instance));
            this.plugin.blockLimits().seed(islandId, census);
        } catch (Exception exception) {
            recountByScan(event.island(), exception);
        }
    }

    /**
     * A dimension entered for the first time comes up after its island: it gets the island's border
     * and its hoppers join the island's count.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandDimensionLoaded(IslandDimensionLoadedEvent event) {
        this.plugin.upgrades().applyWorldBorder(event.island(), event.world());

        UUID islandId = event.island().uniqueId();
        this.plugin.worlds()
                .islandInstances(islandId)
                .stream()
                .filter(instance -> instance.getName().equals(event.world().getName()))
                .findFirst()
                .ifPresent(instance -> {
                    try {
                        this.plugin.blockLimits().seedWorld(islandId, instance.getName(), BlockLimitService.census(instance));
                    } catch (Exception exception) {
                        this.plugin.blockLimits().forget(islandId);
                        recountByScan(event.island(), exception);
                    }
                });
    }

    /** Hoppers stay refused until a full level scan has counted them instead of the failed census. */
    private void recountByScan(Island island, Exception exception) {
        UUID islandId = island.uniqueId();
        this.plugin.getSLF4JLogger().error("Failed to count the hoppers of island {}; scanning it instead", islandId, exception);
        this.plugin.levels().calculate(island).exceptionally(throwable -> {
            this.plugin.getSLF4JLogger().warn("Failed to scan island {}: {}", islandId, throwable.getMessage());
            return null;
        });
    }

    /**
     * Drops the block counts of an island whose world has left this server: they describe blocks we
     * can no longer see, and the scan that runs when it next loads here seeds them again.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandWorldUnloaded(IslandWorldUnloadedEvent event) {
        this.plugin.blockLimits().forget(event.island().uniqueId());
        this.plugin.levels().forget(event.island().uniqueId());
        this.plugin.biomes().forget(event.island().uniqueId());
    }

    /**
     * Stops a banned player, and a visitor to a closed island, from teleporting in — this covers
     * warps, {@code /is go} and any other plugin's teleport into the world. A dimension also needs
     * the island to have unlocked it and the player to hold its permission.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        World target = event.getTo().getWorld();
        if (target.equals(event.getFrom().getWorld()))
            return;

        Island island = this.plugin.worlds()
                .findByWorld(target)
                .orElse(null);
        if (island == null)
            return;

        Player player = event.getPlayer();
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island);
        ASMessages denial = denialFor(island, player);
        if (denial == null) {
            IslandDimension dimension = this.plugin.worlds()
                    .findDimension(target)
                    .flatMap(id -> this.plugin.dimensions().findById(id))
                    .orElse(null);
            if (dimension != null) {
                denial = this.plugin.dimensions().denial(player, island, dimension);
                placeholders.registerDirect("dimension", dimension.id()).registerDirect("dimension_name", dimension.displayName());
            } else if (this.plugin.worlds().findDimension(target).isPresent())
                // A dimension since removed from the configuration: nobody goes there anymore.
                denial = ASMessages.DIMENSION_UNKNOWN;
        }
        if (denial == null)
            return;

        event.setCancelled(true);
        denial.message(player, placeholders);
    }

    /**
     * Falling into the void on an island sends the player back to its spawn instead of killing
     * them (and dropping their inventory into it) — the spawn of the dimension they fell in, if any.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID
            || !(event.getEntity() instanceof Player player)
            || !this.plugin.configuration().voidTeleport())
            return;

        World world = player.getWorld();
        if (this.plugin.worlds().findIslandIdByWorld(world).isEmpty())
            return;

        event.setCancelled(true);
        IslandDimension dimension = this.plugin.worlds()
                .findDimension(world)
                .flatMap(id -> this.plugin.dimensions().findById(id))
                .orElse(null);
        Location spawn = dimension != null
                ? this.plugin.dimensions().spawn(dimension, world)
                : this.plugin.worlds()
                        .findByWorld(world)
                        .map(island -> island.spawnLocation(world))
                        .orElse(world.getSpawnLocation());
        player.setFallDistance(0);
        player.teleportAsync(spawn);
    }

    /**
     * A bed or respawn anchor inside an island must not bring back someone it has since barred: a
     * respawn is not a teleport, so {@link #onTeleport} never sees it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        // Dying in a dimension with no bed or anchor to go back to: back to the island, rather than
        // to wherever this server spawns players.
        if (!event.isBedSpawn() && !event.isAnchorSpawn()
            && this.plugin.worlds().findDimension(event.getPlayer().getWorld()).isPresent())
            this.plugin.worlds()
                    .findIslandIdByWorld(event.getPlayer().getWorld())
                    .ifPresent(islandId -> this.plugin.worlds()
                            .findByIslandId(islandId)
                            .flatMap(instance -> this.plugin.islands().repository().findCachedById(islandId)
                                    .map(island -> island.spawnLocation(instance.getBukkitWorld())))
                            .ifPresent(event::setRespawnLocation));

        World target = event.getRespawnLocation().getWorld();
        Island island = target == null ? null : this.plugin.worlds().findByWorld(target).orElse(null);
        if (island == null)
            return;

        Player player = event.getPlayer();
        ASMessages denial = denialFor(island, player);
        if (denial == null)
            return;

        World fallback = Bukkit.getWorlds().stream()
                .filter(world -> this.plugin.worlds().findByWorld(world).isEmpty())
                .findFirst()
                .orElse(null);
        if (fallback == null)
            return; // nowhere local to send them; they are evicted on arrival below

        event.setRespawnLocation(fallback.getSpawnLocation());
        denial.message(player, AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island));
    }

    /**
     * Evicts a player who logs back in inside an island that has since barred them — they were
     * banned, or the island was closed, while they were offline.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Island island = this.plugin.worlds()
                .findByWorld(player.getWorld())
                .orElse(null);
        if (island == null)
            return;

        ASMessages denial = denialFor(island, player);
        if (denial == null)
            return;

        denial.message(player, AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island));
        this.plugin.bans().evict(island.uniqueId(), player.getUniqueId());
    }

    /**
     * Why this player may not be in this island's world, or {@code null} when they may. A ban is
     * checked first: it is the stronger of the two, and outlives the island being reopened.
     */
    private ASMessages denialFor(Island island, Player player) {
        if (island.isBanned(player.getUniqueId()))
            return ASMessages.BANNED_FROM_ISLAND;
        if (island.locked() && !this.plugin.islands().mayEnterClosed(island, player))
            return ASMessages.ISLAND_IS_CLOSED;
        return null;
    }
}
