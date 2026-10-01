package com.astralrealms.skyblock.event.island;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

/**
 * Fired after an island changes owner. The previous owner now holds the island's highest member role.
 */
@Getter
public class IslandOwnershipTransferredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID previousOwner;
    private final UUID newOwner;

    public IslandOwnershipTransferredEvent(Island island, UUID previousOwner, UUID newOwner) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.previousOwner = previousOwner;
        this.newOwner = newOwner;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
