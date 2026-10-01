package com.astralrealms.skyblock.command.context;

import java.util.UUID;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.repository.IslandRepository;

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

        if (name == null && !context.hasFlag("real")) {
            // "Your island" means nothing for the console.
            if (context.getPlayer() == null)
                throw new InvalidCommandArgument("Specify an island name.");
            return this.plugin.members()
                    .findPlayerIsland(context.getPlayer().getUniqueId())
                    .orElseThrow(() -> new InvalidCommandArgument("You are not a member of any island. Please specify an island name."));
        }

        IslandRepository repository = this.plugin.islands().repository();
        Island island = repository.findByName(name).orElse(null);
        if (island != null)
            return island;

        // Known but not loaded here yet (warmup still running, or evicted): start loading it. A
        // command resolver cannot wait, so the player retries once it is in.
        UUID islandId = repository.findIdByName(name)
                .orElseThrow(() -> new InvalidCommandArgument("Island not found: " + name));
        repository.findById(islandId);
        throw new InvalidCommandArgument("Island " + name + " is loading, please try again in a moment.");
    }
}
