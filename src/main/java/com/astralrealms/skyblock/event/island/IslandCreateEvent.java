package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.astralrealms.skyblock.model.island.Island;

import lombok.Getter;

@Getter
public class IslandCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Island island;
    /** The island's world, or {@code null} when it was created on another server. */
    @Nullable
    private final World world;

    public IslandCreateEvent(Player player, Island island, @Nullable World world) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.island = island;
        this.world = world;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
