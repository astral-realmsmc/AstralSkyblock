package com.astralrealms.skyblock.event;

import com.astralrealms.skyblock.model.island.Island;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class IslandMemberJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerId;
    private final UUID invitedBy;

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
