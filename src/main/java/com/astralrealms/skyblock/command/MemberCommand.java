package com.astralrealms.skyblock.command;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;


@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class MemberCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("kick")
    @Description("Kicks a member from your island")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onKick(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.KICK_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.members()
                .kick(island, player, target.uniqueId())
                .thenAccept(v -> player.sendMessage(Component.text(target.name() + " has been kicked from the island.")));
    }

    @Subcommand("leave")
    @Description("Leaves your island")
    public void onLeave(Player player) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        this.plugin.members()
                .leave(island, player)
                .thenAccept(v -> player.sendMessage(Component.text("You have left the island.")));
    }

    @Subcommand("promote")
    @Description("Promotes a member to the next role")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onPromote(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.PROMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        this.plugin.members()
                .promote(island, player, target.uniqueId())
                .thenAccept(v -> player.sendMessage(Component.text(target.name() + " has been promoted.")));
    }

    @Subcommand("demote")
    @Description("Demotes a member to the previous role")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onDemote(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.DEMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.members()
                .demote(island, player, target.uniqueId())
                .thenAccept(v -> player.sendMessage(Component.text(target.name() + " has been demoted.")));
    }

    @Subcommand("transfer")
    @Description("Transfers island ownership to a member")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onTransfer(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null || island.owner() == null
            || !island.owner().playerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You are not the owner of an island."));
            return;
        }
        IslandMember newOwner = island.findMember(target.uniqueId()).orElse(null);
        if (newOwner == null) {
            player.sendMessage(Component.text(target.name() + " is not a member of your island."));
            return;
        }
        this.plugin.members()
                .transfer(island, player, newOwner)
                .thenAccept(v -> player.sendMessage(Component.text("Island ownership transferred to " + target.name() + ".")));
    }
}
