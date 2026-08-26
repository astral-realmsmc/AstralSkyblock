package com.astralrealms.skyblock.placeholder.level;

import java.util.List;

import com.astralrealms.core.placeholder.Placeholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.skyblock.model.island.Island;

/** The cached island leaderboard, exposed to menus as ranked entries. */
public class TopIslandItemProvider implements ItemProvider {

    private final List<Island> islands;

    public TopIslandItemProvider(List<Island> islands) {
        this.islands = islands;
    }

    @Override
    public Placeholder item(int index) {
        return new TopIslandPlaceholder(index + 1, this.islands.get(index));
    }

    @Override
    public int size() {
        return this.islands.size();
    }
}
