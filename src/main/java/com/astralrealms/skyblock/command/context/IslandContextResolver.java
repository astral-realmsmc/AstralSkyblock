package com.astralrealms.skyblock.command.context;

import java.util.UUID;

import com.astralrealms.skyblock.AstralSkyblock;
import org.bukkit.entity.Player;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.configuration.ASMessages;
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
                throw fail(context, ASMessages.COMMAND_SPECIFY_ISLAND, null);
            return this.plugin.members()
                    .findPlayerIsland(context.getPlayer().getUniqueId())
                    .orElseThrow(() -> fail(context, ASMessages.COMMAND_SPECIFY_ISLAND, null));
        }

        IslandRepository repository = this.plugin.islands().repository();
        Island island = repository.findByName(name).orElse(null);
        if (island != null)
            return island;

        // Known but not loaded here yet (warmup still running, or evicted): start loading it. A
        // command resolver cannot wait, so the player retries once it is in.
        UUID islandId = repository.findIdByName(name)
                .orElseThrow(() -> fail(context, ASMessages.ISLAND_NOT_FOUND, name));
        repository.findById(islandId);
        throw fail(context, ASMessages.ISLAND_LOADING, name);
    }

    /** Sends {@code message} (from messages.yml) and returns the silent failure ACF expects. */
    static InvalidCommandArgument fail(BukkitCommandExecutionContext context, ASMessages message, String name) {
        Player player = context.getPlayer();
        message.message(context.getSender(), AstralPaperAPI.createPlaceholderContainer(player).registerDirect("name", name == null ? "" : name));
        return new InvalidCommandArgument(false);
    }
}
