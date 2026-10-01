package com.astralrealms.skyblock.command.completion;

import java.util.Collection;
import java.util.Locale;

import com.astralrealms.skyblock.AstralSkyblock;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private static final int MAX_COMPLETIONS = 50;

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        // Every island name on the network, on every keystroke: filter by what is typed and cap it.
        String typed = context.getInput() == null ? "" : context.getInput().toLowerCase(Locale.ROOT);
        return this.plugin.islands().repository().names().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(MAX_COMPLETIONS)
                .toList();
    }
}
