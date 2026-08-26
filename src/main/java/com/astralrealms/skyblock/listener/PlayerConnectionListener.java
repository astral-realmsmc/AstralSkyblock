package com.astralrealms.skyblock.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlayerConnectionListener implements Listener {

    /** Grace period before the rejoin check, long enough for an incoming network teleport to land. */
    private static final long RESTORE_DELAY_TICKS = 40L;

    private final AstralSkyblock plugin;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.plugin.players().load(event.getPlayer());
    }

    /**
     * Puts a player back on their island when the island world they logged out in is no longer
     * loaded here — it was idle-unloaded, or it is now hosted on another server — in which case
     * Bukkit dropped them into this server's default world on login.
     *
     * <p>The check is deferred by {@link #RESTORE_DELAY_TICKS}: a player arriving from another
     * server lands in the default world for a moment before the network teleport that brought them
     * here resolves, and restoring them in that window would hijack the destination they actually
     * asked for (a warp, or somebody else's island). By the time the check runs, such a player has
     * either been placed in an island world or is flagged as teleported on join, and is left alone.
     *
     * <p>Players with no island are also left alone; only island servers do this at all, since
     * standing outside an island world is the normal state everywhere else.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRejoin(PlayerJoinEvent event) {
        if (!this.plugin.configuration().isIslandServer())
            return;

        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> restore(player), RESTORE_DELAY_TICKS);
    }

    private void restore(Player player) {
        if (!player.isOnline())
            return;

        TeleportationService teleportation = AstralPaperAPI.getService(TeleportationService.class)
                .orElseThrow();
        if (teleportation.wasTeleportedOnJoin(player.getUniqueId()))
            return; // the network teleport that brought them here owns where they end up

        if (this.plugin.worlds().findByWorld(player.getWorld()).isPresent())
            return; // already in an island world that is loaded here

        Island island = this.plugin.members()
                .findPlayerIsland(player.getUniqueId())
                .orElse(null);
        if (island == null)
            return;

        this.plugin.islands()
                .spawnIsland(island)
                .whenComplete((location, throwable) -> {
                    if (throwable != null || location == null) {
                        this.plugin.getSLF4JLogger().error("Failed to restore {} to island {} on join",
                                player.getName(), island.uniqueId(), throwable);
                        return;
                    }

                    teleportation.teleport(player.getUniqueId(), location)
                            .exceptionally(error -> {
                                this.plugin.getSLF4JLogger().error("Failed to teleport {} back to island {} on join",
                                        player.getName(), island.uniqueId(), error);
                                return null;
                            });
                });
    }
}
