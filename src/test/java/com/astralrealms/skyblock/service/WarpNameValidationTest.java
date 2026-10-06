package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarpNameValidationTest {

    @Test
    void acceptsASimpleName() {
        assertTrue(WarpService.isValidName("farm_1"));
    }

    @Test
    void refusesNamesWithSpaces() {
        assertFalse(WarpService.isValidName("my farm"));
    }

    @Test
    void refusesWarpActionNames() {
        // /is warp set would create a warp instead of teleporting to one named "set".
        assertFalse(WarpService.isValidName("set"));
        assertFalse(WarpService.isValidName("Delete"));
        assertTrue(WarpService.isValidName("settings"));
    }
}
