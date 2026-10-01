package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

class UpgradeLevelFallbackTest {

    private static IslandUpgrade.Level level(int level, double value) {
        return new IslandUpgrade.Level(level, 0, null, null, value, "tier-" + level, null);
    }

    private final IslandUpgrade blueprint = new IslandUpgrade(UpgradeType.GENERATOR, null, null,
            Map.of(0, level(0, 1), 2, level(2, 3)));

    @Test
    void returnsTheExactLevel() {
        assertEquals(3, UpgradeService.levelAtOrBelow(blueprint, 2).value());
    }

    @Test
    void fallsBackToTheHighestLevelBelow() {
        // Level 1 was trimmed from the blueprint: the island keeps level 0's effect, and level 5
        // (past the maximum) keeps level 2's, rather than dropping to nothing.
        assertEquals("tier-0", UpgradeService.levelAtOrBelow(blueprint, 1).key());
        assertEquals("tier-2", UpgradeService.levelAtOrBelow(blueprint, 5).key());
    }

    @Test
    void isEmptyBelowEveryLevel() {
        assertNull(UpgradeService.levelAtOrBelow(blueprint, -1));
    }
}
