package com.astralrealms.skyblock.command.completion;

import java.util.Collection;
import java.util.List;

import org.bukkit.entity.Player;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

/** Completes the warp names of the sender's own island. */
@RequiredArgsConstructor
public class IslandWarpCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        Player sender = context.getPlayer();
        if (sender == null)
            return List.of();

        return this.plugin.members()
                .findPlayerIsland(sender.getUniqueId())
                .map(Island::warps)
                .orElse(List.of())
                .stream()
                .map(warp -> warp.name())
                .toList();
    }
}
