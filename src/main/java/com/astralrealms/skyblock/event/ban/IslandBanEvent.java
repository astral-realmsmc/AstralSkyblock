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
 * Fired after a player is banned from an island.
 */
@Getter
public class IslandBanEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    /** The banned player. */
    private final UUID playerUuid;
    /** Who banned them; {@code null} for the console. */
    private final @Nullable UUID bannedBy;
    private final @Nullable String reason;

    public IslandBanEvent(Island island, UUID playerUuid, @Nullable UUID bannedBy, @Nullable String reason) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.playerUuid = playerUuid;
        this.bannedBy = bannedBy;
        this.reason = reason;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
