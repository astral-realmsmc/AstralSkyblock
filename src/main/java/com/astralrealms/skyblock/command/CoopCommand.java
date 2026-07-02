package com.astralrealms.skyblock.command;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;

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
            ASMessages.NO_ISLAND.message(player);
            return;
        }
        if (!island.hasPermission(player, IslandPermission.UNCOOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
        this.plugin.coops().remove(island, player, target.uniqueId());
    }
}
