package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BlockLimitServiceTest {

    private final BlockLimitService limits = new BlockLimitService();
    private final UUID island = UUID.randomUUID();

    @Test
    void isUncountedUntilSeeded() {
        assertFalse(limits.isCounted(island));
        limits.addHopper(island, 0, 0); // ignored: the coming census sees it
        limits.reconcile(island, 0, 0, 5); // ignored too
        assertFalse(limits.isCounted(island));

        limits.seed(island, Map.of(BlockLimitService.chunkKey(0, 0), 1));
        assertTrue(limits.isCounted(island));
        assertEquals(1, limits.hoppers(island));
    }

    @Test
    void sumsEveryChunk() {
        limits.seed(island, Map.of(BlockLimitService.chunkKey(0, 0), 2, BlockLimitService.chunkKey(3, -1), 3));
        limits.addHopper(island, 100, 100);
        assertEquals(6, limits.hoppers(island));
    }

    @Test
    void reconcileReplacesOnlyThatChunk() {
        limits.seed(island, Map.of(BlockLimitService.chunkKey(0, 0), 2, BlockLimitService.chunkKey(1, 0), 3));
        // A piston pushed a hopper the events never saw: chunk (0,0) really holds 4.
        limits.reconcile(island, 0, 0, 4);
        assertEquals(7, limits.hoppers(island));
        limits.reconcile(island, 1, 0, 0);
        assertEquals(4, limits.hoppers(island));
    }

    @Test
    void neverGoesBelowZero() {
        limits.seed(island, Map.of());
        limits.removeHopper(island, 0, 0);
        assertEquals(0, limits.hoppers(island));
        limits.addHopper(island, 0, 0);
        assertEquals(1, limits.hoppers(island));
    }
}
