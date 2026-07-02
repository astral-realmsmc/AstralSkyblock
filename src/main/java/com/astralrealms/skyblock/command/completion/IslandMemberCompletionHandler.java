package com.astralrealms.skyblock.command.completion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.InvalidCommandArgument;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandMemberCompletionHandler implements CommandCompletions.CommandCompletionHandler<BukkitCommandCompletionContext> {

    private final AstralSkyblock plugin;

    @Override
    public Collection<String> getCompletions(BukkitCommandCompletionContext context) throws InvalidCommandArgument {
        Player sender = context.getPlayer();
        if (sender == null)
            return List.of();

        Collection<IslandMember> members = new ArrayList<>(this.plugin.members()
                .findPlayerIsland(sender.getUniqueId())
                .map(Island::members)
                .orElse(List.of()));
        if (members.isEmpty())
            return List.of();

        return members.stream()
                .filter(member -> !member.playerUuid().equals(sender.getUniqueId()))
                .map(member -> AstralPaperAPI.players().findByUniqueId(member.playerUuid()).orElse(null))
                .filter(Objects::nonNull)
                .map(MinecraftPlayer::name)
                .toList();
    }

}
