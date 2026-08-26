package com.astralrealms.skyblock.placeholder.level;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

/** One leaderboard row: an island plus the rank it currently holds. */
@RequiredArgsConstructor
public class TopIslandPlaceholder implements ComplexPlaceholder {

    private final int rank;
    private final Island island;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this.island;

        return switch (context.next()) {
            case "rank" -> this.rank;
            case "island" -> this.island;
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "entry";
    }
}
