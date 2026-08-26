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
     * Stops a banned player from teleporting into the island they are banned from — this covers
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
        if (island == null || !island.isBanned(event.getPlayer().getUniqueId()))
            return;

        event.setCancelled(true);
        ASMessages.BANNED_FROM_ISLAND.message(
                event.getPlayer(),
                AstralPaperAPI.createPlaceholderContainer(event.getPlayer()).registerPlaceholder(island));
    }

    /**
     * Evicts a player who logs back in inside an island they were banned from while offline.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Island island = this.plugin.worlds()
                .findByWorld(player.getWorld())
                .orElse(null);
        if (island == null || !island.isBanned(player.getUniqueId()))
            return;

        ASMessages.BANNED_FROM_ISLAND.message(
                player,
                AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island));
        this.plugin.bans().evict(island.uniqueId(), player.getUniqueId());
    }
}
