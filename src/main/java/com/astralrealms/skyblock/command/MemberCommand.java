package com.astralrealms.skyblock.command;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;


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
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (!island.hasPermission(player, IslandPermission.KICK_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.members().kick(island, player, target.uniqueId());
    }

    @Subcommand("leave")
    @Description("Leaves your island")
    public void onLeave(Player player) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        this.plugin.members().leave(island, player);
    }

    @Subcommand("promote")
    @Description("Promotes a member to the next role")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onPromote(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (!island.hasPermission(player, IslandPermission.PROMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.members().promote(island, player, target.uniqueId());
    }

    @Subcommand("demote")
    @Description("Demotes a member to the previous role")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onDemote(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (!island.hasPermission(player, IslandPermission.DEMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.members().demote(island, player, target.uniqueId());
    }

    @Subcommand("transfer")
    @Description("Transfers island ownership to a member")
    @Syntax("<player>")
    @CommandCompletion("@islandMembers")
    public void onTransfer(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (island.owner() == null || !island.owner().playerUuid().equals(player.getUniqueId())) {
            ASMessages.NOT_ISLAND_OWNER.message(player);
            return;
        }
        IslandMember newOwner = island.findMember(target.uniqueId()).orElse(null);
        if (newOwner == null) {
            PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                    .registerPlaceholder(island)
                    .registerDirect("target", new MinecraftPlayerPlaceholder(target));
            ASMessages.MEMBER_NOT_FOUND.message(player, placeholders);
            return;
        }
        this.plugin.members().transfer(island, player, newOwner);
    }
}
