package com.astralrealms.skyblock.command;

import java.util.List;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class InvitationCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("invite")
    @Description("Invites a player to your island")
    @Syntax("<player>")
    @CommandCompletion("@networkPlayers")
    public void onInvite(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        } else if (!island.hasPermission(player, IslandPermission.INVITE_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        this.plugin.invitations()
                .create(island, player, target, InvitationType.MEMBER);
    }

    @Subcommand("coop")
    @Description("Sends a coop invitation to a player")
    @Syntax("<player>")
    @CommandCompletion("@networkPlayers")
    public void onCoop(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (!island.hasPermission(player, IslandPermission.COOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.invitations()
                .create(island, player, target, InvitationType.COOP);
    }

    @Subcommand("accept")
    @Description("Accepts a pending island invitation")
    @Syntax("[player]")
    @CommandCompletion("@networkPlayers")
    public void onAccept(Player player, @Optional @Nullable MinecraftPlayer sender) {
        if (sender != null) {
            this.plugin.invitations()
                    .findByRecipient(player.getUniqueId())
                    .thenAccept(invites ->
                            invites.stream()
                                    .filter(i -> i.senderId().equals(sender.uniqueId()) && !i.expired())
                                    .findFirst()
                                    .ifPresentOrElse(
                                            i -> this.plugin.invitations().accept(player, i.islandId()),
                                            () -> ASMessages.NO_PENDING_INVITATION.message(player)));
            return;
        }

        this.plugin.invitations()
                .findByRecipient(player.getUniqueId())
                .thenAccept(invites -> {
                    List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
                    if (valid.isEmpty()) {
                        ASMessages.NO_PENDING_INVITATION.message(player);
                        return;
                    } else if (valid.size() > 1) {
                        ASMessages.MULTIPLE_PENDING_INVITATIONS.message(player);
                        return;
                    }

                    this.plugin.invitations().accept(player, valid.getFirst().islandId());
                });
    }

    @Subcommand("decline")
    @Description("Declines a pending island invitation")
    @Syntax("[player]")
    @CommandCompletion("@networkPlayers")
    public void onDecline(Player player, @Optional @Nullable MinecraftPlayer sender) {
        if (sender != null) {
            this.plugin.invitations()
                    .findByRecipient(player.getUniqueId())
                    .thenAccept(invites ->
                            invites.stream()
                                    .filter(i -> i.senderId().equals(sender.uniqueId()) && !i.expired())
                                    .findFirst()
                                    .ifPresentOrElse(i -> this.plugin.invitations().decline(player, i.islandId()),
                                            () -> ASMessages.NO_PENDING_INVITATION.message(player)));
            return;
        }

        this.plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites -> {
            List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
            if (valid.isEmpty()) {
                ASMessages.NO_PENDING_INVITATION.message(player);
                return;
            } else if (valid.size() > 1) {
                ASMessages.MULTIPLE_PENDING_INVITATIONS.message(player);
                return;
            }

            this.plugin.invitations().decline(player, valid.getFirst().islandId());
        });
    }

    @Subcommand("cancel")
    @Description("Cancels an outgoing island invitation")
    @Syntax("<player>")
    @CommandCompletion("@networkPlayers")
    public void onCancel(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }

        this.plugin.invitations().cancel(island, player, target);
    }
}
