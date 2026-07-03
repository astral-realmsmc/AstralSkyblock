package com.astralrealms.skyblock.event.island.world;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class IslandWorldUnloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final World world;

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
