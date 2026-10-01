package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

/**
 * Fired after an island's new name is stored. On the server where the rename was made.
 */
@Getter
public class IslandRenamedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    /** Who renamed it. */
    private final Player player;
    /** The name before; {@code null} for an unnamed island. */
    private final @Nullable String previousName;
    private final String name;

    public IslandRenamedEvent(Island island, Player player, @Nullable String previousName, String name) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.player = player;
        this.previousName = previousName;
        this.name = name;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
