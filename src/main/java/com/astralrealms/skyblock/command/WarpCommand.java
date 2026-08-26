package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

/**
 * Warp management. Every subcommand operates on the sender's own island except {@code /is warp},
 * which accepts an island so players can warp to someone else's public warps.
 */
@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class WarpCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("warp")
    @Description("Teleports you to a warp")
    @Syntax("<warp> [island]")
    @CommandCompletion("@islandWarps @islands")
    public void onWarp(Player player, String warp, @co.aikar.commands.annotation.Optional Island island) {
        Island target = island != null ? island : ownIsland(player);
        if (target == null)
            return;
        this.plugin.warps().teleport(target, player, warp);
    }

    @Subcommand("warps")
    @Description("Opens the warp menu of an island")
    @Syntax("[island]")
    @CommandCompletion("@islands")
    public void onWarps(Player player, @co.aikar.commands.annotation.Optional Island island) {
        Island target = island != null ? island : ownIsland(player);
        if (target == null)
            return;

        this.plugin.menus()
                .computeAndOpen(player, "island-warps", Map.of("island", target))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open island warps menu for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    @Subcommand("setwarp")
    @Description("Creates a warp at your position, using the item in your hand as its icon")
    @Syntax("<name>")
    public void onSetWarp(Player player, String name) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().create(island, player, name);
    }

    @Subcommand("delwarp")
    @Description("Deletes one of your island's warps")
    @Syntax("<warp>")
    @CommandCompletion("@islandWarps")
    public void onDeleteWarp(Player player, String warp) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().delete(island, player, warp);
    }

    @Subcommand("movewarp")
    @Description("Moves one of your island's warps to your position")
    @Syntax("<warp>")
    @CommandCompletion("@islandWarps")
    public void onMoveWarp(Player player, String warp) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().relocate(island, player, warp);
    }

    @Subcommand("warpicon")
    @Description("Uses the item in your hand as a warp's icon")
    @Syntax("<warp>")
    @CommandCompletion("@islandWarps")
    public void onWarpIcon(Player player, String warp) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().setIcon(island, player, warp);
    }

    @Subcommand("warpname")
    @Description("Sets the display name of a warp; omit the name to clear it")
    @Syntax("<warp> [display name]")
    @CommandCompletion("@islandWarps")
    public void onWarpName(Player player, String warp, @co.aikar.commands.annotation.Optional String displayName) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().setDisplayName(island, player, warp, displayName);
    }

    @Subcommand("warpdescription|warpdesc")
    @Description("Sets the description of a warp; separate lines with '|'. Omit it to clear.")
    @Syntax("<warp> [description]")
    @CommandCompletion("@islandWarps")
    public void onWarpDescription(Player player, String warp, @co.aikar.commands.annotation.Optional String description) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().setDescription(island, player, warp, description);
    }

    @Subcommand("warpprivate")
    @Description("Toggles a warp between public and members-only")
    @Syntax("<warp>")
    @CommandCompletion("@islandWarps")
    public void onWarpPrivate(Player player, String warp) {
        Island island = ownIsland(player);
        if (island == null)
            return;
        this.plugin.warps().togglePrivate(island, player, warp);
    }

    private Island ownIsland(Player player) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null)
            ASMessages.NO_ISLAND.message(player);
        return island;
    }
}
