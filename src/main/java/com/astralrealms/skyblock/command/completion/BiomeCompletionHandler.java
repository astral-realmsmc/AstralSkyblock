package com.astralrealms.skyblock.command.completion;

import java.util.Collection;

import com.astralrealms.skyblock.AstralSkyblock;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

/** Completes the biomes registered on this server, by their short key ({@code plains}, ...). */
@RequiredArgsConstructor
public class BiomeCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        return this.plugin.biomes().biomeNames();
    }
}
