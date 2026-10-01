package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

/**
 * Fired after an island is closed to visitors, or opened again. On the server where it was changed.
 */
@Getter
public class IslandLockChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    /** Who changed it. */
    private final Player player;
    /** Whether the island is now closed. */
    private final boolean locked;

    public IslandLockChangedEvent(Island island, Player player, boolean locked) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.player = player;
        this.locked = locked;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
