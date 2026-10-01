package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

/**
 * Fired after a scan stores a new value for an island, on the server hosting it.
 */
@Getter
public class IslandLevelChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final long previousLevel;
    private final long level;
    /** The summed block value behind the level. */
    private final long value;

    public IslandLevelChangedEvent(Island island, long previousLevel, long level, long value) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.previousLevel = previousLevel;
        this.level = level;
        this.value = value;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
