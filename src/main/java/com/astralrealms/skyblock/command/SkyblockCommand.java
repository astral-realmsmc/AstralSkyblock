package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;

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

    @Subcommand("info")
    @Syntax("<island>")
    @Description("Displays information about your island")
    @CommandCompletion("@islands")
    public void onInfo(Player player, Island island) {
        player.sendMessage("Island: " + island.name());
        player.sendMessage("Owner: " + (island.owner() != null ? island.owner().playerUuid() : "None"));
        player.sendMessage("Members:");
        island.members().forEach(member -> {
            player.sendMessage("- " + member.playerUuid() + " (Role: " + member.roleId() + ")");
            island.roles()
                    .stream()
                    .filter(role -> role.id().equals(member.roleId()))
                    .findFirst()
                    .ifPresent(role -> player.sendMessage("  Role Name: " + role.name() + " with " + role.permissions().size() + " permissions"));
        });
        player.sendMessage("Roles:");
        island.roles().forEach(role -> {
            player.sendMessage("- " + role.name() + " (Weight: " + role.weight() + ", Default: " + role.isDefault() + ")");
            player.sendMessage("  Permissions: " + role.permissions());
        });
    }

    @Subcommand("save")
    @Syntax("<island>")
    @Description("Saves your island")
    @CommandCompletion("@islands")
    public void onSave(Player player, Island island) {
        this.plugin.worlds()
                .save(island.uniqueId())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to save island {} requested by {}", island.uniqueId(), player.getName(), throwable);
                    return null;
                });
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
}
