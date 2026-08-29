package com.astralrealms.skyblock.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.island.world.IslandWorldLoadedEvent;
import com.astralrealms.skyblock.event.island.world.IslandWorldUnloadedEvent;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

/**
 * Island-wide enforcement that is not settings- or permission-specific: keeping banned players out
 * of island worlds and applying an island's world-state upgrades when its world comes up.
 */
@RequiredArgsConstructor
public class IslandListener implements Listener {

    private final AstralSkyblock plugin;

    /**
     * Applies the island's world-state upgrades (currently its border) as soon as its world is
     * loaded on this server.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandWorldLoaded(IslandWorldLoadedEvent event) {
        this.plugin.upgrades().applyEffects(event.island());

        // First chance to score the island since it came up here; the periodic rescan takes over
        // from now on. A scan already in flight (or a world unloaded meanwhile) is not an error.
        this.plugin.levels()
                .calculate(event.island())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().debug("Skipped the load-time level scan of island {}: {}",
                            event.island().uniqueId(), throwable.getMessage());
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
    }

    /**
     * Stops a banned player, and a visitor to a closed island, from teleporting in — this covers
     * warps, {@code /is go} and any other plugin's teleport into the world.
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
        ASMessages denial = denialFor(island, player);
        if (denial == null)
            return;

        event.setCancelled(true);
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
