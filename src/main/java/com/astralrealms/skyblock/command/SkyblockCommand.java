package com.astralrealms.skyblock.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class SkyblockCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Default
    public void onDefault(Player player) {
        Island island = this.plugin.members()
                .findPlayerIsland(player.getUniqueId())
                .orElse(null);
        if (island == null) {
            // TODO: Open creation menu
            return;
        }

        this.plugin.menus()
                .computeAndOpen(player, "island-main", Map.of("island", island))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open island main menu for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    @Subcommand("create")
    @Description("Creates a new island")
    @Syntax("<name> <blueprint>")
    @CommandCompletion("@nothing @islandBlueprints")
    public void onCreate(Player player, @Nullable @Optional String name, @Nullable @Optional IslandBlueprint blueprint) {
        IslandBlueprint finalBlueprint = blueprint == null ? this.plugin.blueprints().defaultBlueprint() : blueprint;
        this.plugin.islands().create(player, name, finalBlueprint);
    }

    @Subcommand("delete")
    @Description("Deletes your island")
    @Syntax("<island>")
    @CommandCompletion("@islands")
    public void onDelete(Player player, Island island) {
        this.plugin.islands().delete(player, island);
    }

    @Subcommand("go")
    @Description("Teleports you to your island")
    @CommandCompletion("@islands")
    @Syntax("<island>")
    public void onGo(Player player, Island island) {
        this.plugin.islands()
                .spawnIsland(island)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Error while spawning island: {}", island.uniqueId(), throwable);
                        ASMessages.UNEXPECTED_ERROR.message(player);
                        return;
                    }

                    TeleportationService teleportationService = AstralPaperAPI.getService(TeleportationService.class)
                            .orElseThrow(() -> new IllegalStateException("TeleportationService not found"));
                    teleportationService.teleport(player.getUniqueId(), result);
                });
    }

    @Subcommand("reload")
    @CommandPermission("skyblock.reload")
    @Description("Reloads the plugin configuration")
    public void onReload(CommandSender sender) {
        sender.sendMessage(Component.text("Reloading configuration..."));
        try {
            plugin.loadConfiguration();
            sender.sendMessage(Component.text("Configuration reloaded successfully."));
        } catch (Exception e) {
            sender.sendMessage(Component.text("An error occurred while reloading the configuration: " + e.getMessage()));
        }
    }

    // =========================================================================
    //  Invitation commands
    // =========================================================================

    @Subcommand("invite")
    @Description("Invites a player to your island")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onInvite(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.INVITE_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.invitations()
                .create(island, player.getUniqueId(), targetUuid, InvitationType.MEMBER)
                .thenAccept(v -> player.sendMessage(Component.text("Invitation sent to " + targetName + ".")));
    }

    @Subcommand("coop")
    @Description("Sends a coop invitation to a player")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onCoop(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.COOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.invitations()
                .create(island, player.getUniqueId(), targetUuid, InvitationType.COOP)
                .thenAccept(v -> player.sendMessage(Component.text("Coop invitation sent to " + targetName + ".")));
    }

    @Subcommand("accept")
    @Description("Accepts a pending island invitation")
    @Syntax("[player]")
    @CommandCompletion("@players")
    public void onAccept(Player player, @Optional @Nullable String senderName) {
        if (senderName != null) {
            UUID senderUuid = Bukkit.getOfflinePlayer(senderName).getUniqueId();
            this.plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites ->
                    invites.stream()
                            .filter(i -> i.senderId().equals(senderUuid) && !i.expired())
                            .findFirst()
                            .ifPresentOrElse(
                                    i -> this.plugin.invitations()
                                            .accept(i.islandId(), player.getUniqueId())
                                            .thenAccept(v -> player.sendMessage(Component.text("Invitation accepted!"))),
                                    () -> player.sendMessage(Component.text("No pending invitation from " + senderName + "."))));
        } else {
            this.plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites -> {
                List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
                if (valid.isEmpty()) {
                    player.sendMessage(Component.text("You have no pending invitations."));
                    return;
                }
                if (valid.size() > 1) {
                    player.sendMessage(Component.text("You have multiple invitations — use /is accept <player>."));
                    return;
                }
                this.plugin.invitations()
                        .accept(valid.get(0).islandId(), player.getUniqueId())
                        .thenAccept(v -> player.sendMessage(Component.text("Invitation accepted!")));
            });
        }
    }

    @Subcommand("decline")
    @Description("Declines a pending island invitation")
    @Syntax("[player]")
    @CommandCompletion("@players")
    public void onDecline(Player player, @Optional @Nullable String senderName) {
        if (senderName != null) {
            UUID senderUuid = Bukkit.getOfflinePlayer(senderName).getUniqueId();
            this.plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites ->
                    invites.stream()
                            .filter(i -> i.senderId().equals(senderUuid) && !i.expired())
                            .findFirst()
                            .ifPresentOrElse(
                                    i -> this.plugin.invitations()
                                            .decline(i.islandId(), player.getUniqueId())
                                            .thenAccept(v -> player.sendMessage(Component.text("Invitation declined."))),
                                    () -> player.sendMessage(Component.text("No pending invitation from " + senderName + "."))));
        } else {
            this.plugin.invitations().findByRecipient(player.getUniqueId()).thenAccept(invites -> {
                List<IslandInvitation> valid = invites.stream().filter(i -> !i.expired()).toList();
                if (valid.isEmpty()) {
                    player.sendMessage(Component.text("You have no pending invitations."));
                    return;
                }
                if (valid.size() > 1) {
                    player.sendMessage(Component.text("You have multiple invitations — use /is decline <player>."));
                    return;
                }
                this.plugin.invitations()
                        .decline(valid.get(0).islandId(), player.getUniqueId())
                        .thenAccept(v -> player.sendMessage(Component.text("Invitation declined.")));
            });
        }
    }

    @Subcommand("cancel")
    @Description("Cancels an outgoing island invitation")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onCancel(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.invitations()
                .cancel(island, player.getUniqueId(), targetUuid)
                .thenAccept(v -> player.sendMessage(Component.text("Invitation to " + targetName + " cancelled.")));
    }

    // =========================================================================
    //  Member management commands
    // =========================================================================

    @Subcommand("kick")
    @Description("Kicks a member from your island")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onKick(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.KICK_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.members()
                .kick(island, player, targetUuid)
                .thenAccept(v -> player.sendMessage(Component.text(targetName + " has been kicked from the island.")));
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
    @CommandCompletion("@players")
    public void onPromote(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.PROMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.members()
                .promote(island, player, targetUuid)
                .thenAccept(v -> player.sendMessage(Component.text(targetName + " has been promoted.")));
    }

    @Subcommand("demote")
    @Description("Demotes a member to the previous role")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onDemote(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.DEMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        this.plugin.members()
                .demote(island, player, targetUuid)
                .thenAccept(v -> player.sendMessage(Component.text(targetName + " has been demoted.")));
    }

    @Subcommand("transfer")
    @Description("Transfers island ownership to a member")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onTransfer(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null || island.owner() == null
                || !island.owner().playerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You are not the owner of an island."));
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        IslandMember newOwner = island.findMember(targetUuid).orElse(null);
        if (newOwner == null) {
            player.sendMessage(Component.text(targetName + " is not a member of your island."));
            return;
        }
        this.plugin.members()
                .transfer(island, player, newOwner)
                .thenAccept(v -> player.sendMessage(Component.text("Island ownership transferred to " + targetName + ".")));
    }

    // =========================================================================
    //  Coop management commands
    // =========================================================================

    @Subcommand("uncoop")
    @Description("Removes a coop player from your island")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onUncoop(Player player, String targetName) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.UNCOOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        UUID targetUuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        if (!this.plugin.coops().isCoop(island.uniqueId(), targetUuid)) {
            player.sendMessage(Component.text(targetName + " is not a coop member of your island."));
            return;
        }
        this.plugin.coops()
                .remove(island, targetUuid)
                .thenAccept(v -> player.sendMessage(Component.text(targetName + " is no longer a coop member.")));
    }
}
