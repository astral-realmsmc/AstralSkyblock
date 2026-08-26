package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class UpgradeCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("upgrades")
    @Description("Opens the upgrade menu of your island")
    public void onUpgrades(Player player) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }

        this.plugin.menus()
                .computeAndOpen(player, "island-upgrades", Map.of("island", island))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open island upgrades menu for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    @Subcommand("upgrade")
    @Description("Buys the next level of an upgrade for your island")
    @Syntax("<upgrade>")
    @CommandCompletion("@islandUpgrades")
    public void onUpgrade(Player player, UpgradeType type) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        this.plugin.upgrades().purchase(island, player, type);
    }

    @Subcommand("upgrade set")
    @CommandPermission("skyblock.admin")
    @Description("Sets an island's level for an upgrade, free of charge")
    @Syntax("<island> <upgrade> <level>")
    @CommandCompletion("@islands @islandUpgrades @nothing")
    public void onUpgradeSet(CommandSender sender, Island island, UpgradeType type, int level) {
        if (level < 0) {
            sender.sendMessage(Component.text("Level must be zero or greater."));
            return;
        }

        this.plugin.upgrades()
                .setLevel(island.uniqueId(), type, level)
                .whenComplete((saved, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(Component.text("Failed to set upgrade level: " + throwable.getMessage()));
                        this.plugin.getSLF4JLogger().error("Failed to set upgrade {} of island {} to {}", type, island.uniqueId(), level, throwable);
                        return;
                    }
                    this.plugin.upgrades().applyEffects(island.uniqueId());
                    sender.sendMessage(Component.text(type.name() + " of " + island.name() + " is now level " + saved + "."));
                });
    }
}
