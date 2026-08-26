package com.astralrealms.skyblock.placeholder.upgrade;

import java.util.List;

import com.astralrealms.core.placeholder.Placeholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;

/**
 * Every configured upgrade blueprint, bound to an island so each entry also carries that island's
 * current level. Backs {@code %..._island_upgrades%} in the upgrade menu.
 */
public class IslandUpgradeItemProvider implements ItemProvider {

    private final Island island;
    private final List<IslandUpgrade> blueprints;

    public IslandUpgradeItemProvider(Island island) {
        this.island = island;
        this.blueprints = List.copyOf(AstralSkyblock.get().upgrades().blueprints());
    }

    @Override
    public Placeholder item(int index) {
        return new IslandUpgradePlaceholder(this.island, this.blueprints.get(index));
    }

    @Override
    public int size() {
        return this.blueprints.size();
    }
}
