package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IslandNameValidationTest {

    @Test
    void acceptsASingleWord() {
        assertTrue(IslandService.isValidIslandName("Atlantis"));
    }

    @Test
    void refusesBlankNames() {
        assertFalse(IslandService.isValidIslandName(null));
        assertFalse(IslandService.isValidIslandName("   "));
    }

    @Test
    void refusesNamesWithSpaces() {
        // A command argument cannot carry them: /is go <island> would never reach the island.
        assertFalse(IslandService.isValidIslandName("Sky Land"));
    }

    @Test
    void refusesNamesOverTheLimit() {
        assertFalse(IslandService.isValidIslandName("a".repeat(33)));
        assertTrue(IslandService.isValidIslandName("a".repeat(32)));
    }
}
