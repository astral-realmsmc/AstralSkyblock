package com.astralrealms.skyblock.command.completion;

import java.util.Collection;

import org.bukkit.command.CommandSender;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.dimension.IslandDimension;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

/** Completes the dimensions the sender holds the permission of; none while dimensions are disabled. */
@RequiredArgsConstructor
public class IslandDimensionCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        CommandSender sender = context.getSender();
        return this.plugin.dimensions()
                .all()
                .stream()
                .filter(dimension -> sender.hasPermission("skyblock.admin") || sender.hasPermission(dimension.permission()))
                .map(IslandDimension::id)
                .toList();
    }
}
