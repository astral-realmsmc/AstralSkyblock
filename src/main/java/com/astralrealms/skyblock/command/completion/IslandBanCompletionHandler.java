package com.astralrealms.skyblock.command.completion;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

/** Completes the names of players banned from the sender's own island. */
@RequiredArgsConstructor
public class IslandBanCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        Player sender = context.getPlayer();
        if (sender == null)
            return List.of();

        return this.plugin.members()
                .findPlayerIsland(sender.getUniqueId())
                .map(Island::bans)
                .orElse(List.of())
                .stream()
                .map(ban -> AstralPaperAPI.players().findByUniqueId(ban.playerUuid()).orElse(null))
                .filter(Objects::nonNull)
                .map(MinecraftPlayer::name)
                .toList();
    }
}
