package com.astralrealms.skyblock.command;

import java.util.Map;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.service.LevelService;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class LevelCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("calc|level")
    @Description("Recalculates the level of your island")
    @Syntax("[island]")
    @CommandCompletion("@islands")
    public void onCalculate(Player player, @Optional Island island) {
        Island target = island != null ? island : this.plugin.members()
                .findPlayerIsland(player.getUniqueId())
                .orElse(null);
        if (target == null) {
            ASMessages.NO_ISLAND.message(player);
            return;
        }

        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(target);

        long cooldown = this.plugin.levels().cooldownRemaining(target.uniqueId());
        if (cooldown > 0 && !player.hasPermission("skyblock.admin")) {
            ASMessages.LEVEL_COOLDOWN.message(player, placeholders.registerDirect("cooldown", cooldown / 1000));
            return;
        }

        ASMessages.LEVEL_RECALCULATING.message(player, placeholders);
        this.plugin.levels()
                .calculate(target)
                .whenComplete((value, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (cause instanceof LevelService.NotHostedException)
                            ASMessages.LEVEL_NOT_HOSTED.message(player, placeholders);
                        else if (cause instanceof LevelService.ScanInProgressException)
                            ASMessages.LEVEL_IN_PROGRESS.message(player, placeholders);
                        else {
                            ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                            this.plugin.getSLF4JLogger().error("Failed to calculate the level of island {}", target.uniqueId(), throwable);
                        }
                        return;
                    }

                    ASMessages.LEVEL_RECALCULATED.message(player, placeholders);
                });
    }

    @Subcommand("top")
    @Description("Opens the island leaderboard")
    public void onTop(Player player) {
        this.plugin.menus()
                .computeAndOpen(player, "island-top", Map.of())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to open the island leaderboard for {}", player.getName(), throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }
}
