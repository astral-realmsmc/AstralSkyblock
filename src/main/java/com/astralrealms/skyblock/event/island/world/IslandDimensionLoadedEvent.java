package com.astralrealms.skyblock.event.island.world;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A dimension world of an island came up on this server after its overworld did — the first time
 * anyone entered it. The dimensions an island already had load with its overworld and are covered
 * by {@link IslandWorldLoadedEvent} instead.
 */
@Getter
@RequiredArgsConstructor
public class IslandDimensionLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final IslandDimension dimension;
    private final World world;

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
