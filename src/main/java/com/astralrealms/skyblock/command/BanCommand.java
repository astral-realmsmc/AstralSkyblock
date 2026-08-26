package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class BanCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("ban")
    @Description("Bans a player from your island")
    @Syntax("<player> [reason]")
    @CommandCompletion("@players")
    public void onBan(Player player, MinecraftPlayer target, @Nullable @Optional String reason) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        this.plugin.bans().ban(island, player, target.uniqueId(), reason);
    }

    @Subcommand("unban")
    @Description("Lifts a ban on your island")
    @Syntax("<player>")
    @CommandCompletion("@islandBans")
    public void onUnban(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        this.plugin.bans().unban(island, player, target.uniqueId());
    }

    @Subcommand("bans")
    @Description("Opens the ban menu of your island")
    public void onBans(Player player) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }

        this.plugin.menus()
                .computeAndOpen(player, "island-bans", Map.of("island", island))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open island bans menu for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }
}
