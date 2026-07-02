package com.astralrealms.skyblock.command;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import net.kyori.adventure.text.Component;

@CommandAlias("skyblock|is|island")
@Description("Base command for all skyblock commands")
public class CoopCommand extends BaseCommand {

    @Dependency
    private AstralSkyblock plugin;

    @Subcommand("uncoop")
    @Description("Removes a coop player from your island")
    @Syntax("<player>")
    @CommandCompletion("@players")
    public void onUncoop(Player player, MinecraftPlayer target) {
        Island island = this.plugin.members().findPlayerIsland(player.getUniqueId()).orElse(null);
        if (island == null) {
            player.sendMessage(Component.text("You don't have an island."));
            return;
        }
        if (!island.hasPermission(player, IslandPermission.UNCOOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        if (!this.plugin.coops().isCoop(island.uniqueId(), target.uniqueId())) {
            player.sendMessage(Component.text(target.name() + " is not a coop member of your island."));
            return;
        }
        this.plugin.coops()
                .remove(island, target.uniqueId())
                .thenAccept(v -> player.sendMessage(Component.text(target.name() + " is no longer a coop member.")));
    }
}
