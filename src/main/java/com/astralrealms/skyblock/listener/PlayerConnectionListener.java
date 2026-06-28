package com.astralrealms.skyblock.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.astralrealms.skyblock.AstralSkyblock;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlayerConnectionListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.plugin.players().load(event.getPlayer());
    }
}
