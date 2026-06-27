package com.astralrealms.skyblock.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandBlueprint;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class SkyblockCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("create")
    @Description("Creates a new island")
    @Syntax("<blueprint>")
    @CommandCompletion("@islandBlueprints")
    public void onCreate(Player player, @Nullable @Optional IslandBlueprint blueprint) {
        IslandBlueprint finalBlueprint = blueprint == null ? this.plugin.blueprints().defaultBlueprint() : blueprint;
        this.plugin.islands().create(player, finalBlueprint);
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
