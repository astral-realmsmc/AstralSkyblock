package com.astralrealms.skyblock.event.island;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import lombok.Getter;

/**
 * Fired after a player buys an upgrade level and it is stored.
 */
@Getter
public class IslandUpgradePurchasedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final Player player;
    private final UpgradeType type;
    /** The level now held. */
    private final int level;

    public IslandUpgradePurchasedEvent(Island island, Player player, UpgradeType type, int level) {
        super(!Bukkit.isPrimaryThread());
        this.island = island;
        this.player = player;
        this.type = type;
        this.level = level;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
