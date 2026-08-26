package com.astralrealms.skyblock.command.completion;

import java.util.Collection;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

/** Completes the upgrade types that actually have a blueprint configured. */
@RequiredArgsConstructor
public class IslandUpgradeCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        return this.plugin.upgrades()
                .blueprints()
                .stream()
                .map(IslandUpgrade::type)
                .map(UpgradeType::name)
                .toList();
    }
}
