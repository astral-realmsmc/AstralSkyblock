package com.astralrealms.skyblock.command.context;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BukkitCommandExecutionContext;
import co.aikar.commands.InvalidCommandArgument;
import co.aikar.commands.contexts.IssuerAwareContextResolver;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandContextResolver implements IssuerAwareContextResolver<Island, BukkitCommandExecutionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Island getContext(BukkitCommandExecutionContext context) throws InvalidCommandArgument {
        String name = context.popFirstArg();
        if (name == null && context.isOptional())
            return null;

        if (name == null && !context.hasFlag("real"))
            return this.plugin.members()
                    .findPlayerIsland(context.getPlayer().getUniqueId())
                    .orElseThrow(() -> new InvalidCommandArgument("You are not a member of any island. Please specify an island name."));

        return this.plugin.islands()
                .repository()
                .findByName(name)
                .orElseThrow(() -> new InvalidCommandArgument("Island not found: " + name));
    }
}
