package com.astralrealms.skyblock.command.context;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.IslandBlueprint;

import co.aikar.commands.BukkitCommandExecutionContext;
import co.aikar.commands.InvalidCommandArgument;
import co.aikar.commands.contexts.ContextResolver;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandBlueprintContextResolver implements ContextResolver<IslandBlueprint, BukkitCommandExecutionContext> {

    private final AstralSkyblock plugin;

    @Override
    public IslandBlueprint getContext(BukkitCommandExecutionContext context) throws InvalidCommandArgument {
        String name = context.popFirstArg();
        return this.plugin.blueprints()
                .findById(name)
                .orElseThrow(() -> IslandContextResolver.fail(context, ASMessages.BLUEPRINT_NOT_FOUND, name));
    }

}
