package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

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
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender instanceof Player player ? player : null)
                .registerPlaceholder(island)
                .registerDirect("upgrade", type.name())
                .registerDirect("level", level);
        if (level < 0) {
            ASMessages.UPGRADE_SET_NEGATIVE.message(sender, placeholders);
            return;
        }

        // A level past the blueprint has no configured effect, so the island would silently fall
        // back to the highest one that does — set what actually exists instead.
        IslandUpgrade blueprint = this.plugin.upgrades().findByType(type).orElse(null);
        if (blueprint == null) {
            ASMessages.UPGRADE_NOT_CONFIGURED.message(sender, placeholders);
            return;
        }
        if (level > blueprint.maxLevel()) {
            ASMessages.UPGRADE_SET_TOO_HIGH.message(sender, placeholders.registerDirect("maximum", blueprint.maxLevel()));
            return;
        }

        this.plugin.upgrades()
                .setLevel(island.uniqueId(), type, level)
                .whenComplete((saved, throwable) -> {
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to set upgrade {} of island {} to {}", type, island.uniqueId(), level, throwable);
                        return;
                    }
                    this.plugin.upgrades().applyEffects(island.uniqueId());
                    ASMessages.UPGRADE_SET.message(sender, placeholders);
                });
    }
}
