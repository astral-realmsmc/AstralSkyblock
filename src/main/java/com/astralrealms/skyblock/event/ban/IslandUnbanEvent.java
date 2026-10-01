package com.astralrealms.skyblock.event.ban;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

/**
 * Fired after a player's ban from an island is lifted.
 */
@Getter
public class IslandUnbanEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final UUID playerUuid;
    private final @Nullable UUID unbannedBy;

    public IslandUnbanEvent(Island island, UUID playerUuid, @Nullable UUID unbannedBy) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.playerUuid = playerUuid;
        this.unbannedBy = unbannedBy;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
