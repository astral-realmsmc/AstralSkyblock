package com.astralrealms.skyblock.placeholder;

import org.bukkit.entity.Player;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.AstralSkyblock;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SkyblockPlaceholders implements ComplexPlaceholder {

    private final AstralSkyblock plugin;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return null;

        return switch (context.next()) {
            case "player" -> {
                if (!context.hasNext() || !(context.context() instanceof Player player))
                    yield null;

                yield switch (context.next()) {
                    case "island" -> this.plugin.members()
                            .findPlayerIsland(player.getUniqueId())
                            .orElse(null);
                    case null, default -> null;
                };
            }

            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "skyblock";
    }
}
