package com.astralrealms.skyblock.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.astralrealms.core.paper.event.player.PlayerQuitNetworkEvent;
import com.astralrealms.skyblock.AstralSkyblock;

import lombok.RequiredArgsConstructor;

/**
 * Expires an island's coops when its last member leaves the network (not merely this server).
 */
@RequiredArgsConstructor
public class CoopExpiryListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNetworkQuit(PlayerQuitNetworkEvent event) {
        this.plugin.members()
                .findPlayerIsland(event.player().uniqueId())
                .ifPresent(island -> this.plugin.coops().clearIfIslandEmpty(island, event.player().uniqueId()));
    }
}
