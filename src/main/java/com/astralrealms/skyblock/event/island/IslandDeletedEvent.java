package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

@Getter
public class IslandDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Island island;

    public IslandDeletedEvent(Player player, Island island) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.island = island;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
