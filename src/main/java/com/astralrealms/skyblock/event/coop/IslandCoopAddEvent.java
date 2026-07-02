package com.astralrealms.skyblock.event.coop;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class IslandCoopAddEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;
    private final UUID addedBy;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
