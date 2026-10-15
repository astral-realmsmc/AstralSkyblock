package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BlockLimitServiceTest {

    private static final String WORLD = "overworld-world";
    private static final String NETHER = "nether-world";

    private final BlockLimitService limits = new BlockLimitService();
    private final UUID island = UUID.randomUUID();

    @Test
    void isUncountedUntilSeeded() {
        assertFalse(limits.isCounted(island));
        limits.addHopper(island, WORLD, 0, 0); // ignored: the coming census sees it
        limits.reconcile(island, WORLD, 0, 0, 5); // ignored too
        limits.seedWorld(island, NETHER, Map.of(BlockLimitService.chunkKey(0, 0), 3)); // and this
        assertFalse(limits.isCounted(island));

        limits.seed(island, Map.of(WORLD, Map.of(BlockLimitService.chunkKey(0, 0), 1)));
        assertTrue(limits.isCounted(island));
        assertEquals(1, limits.hoppers(island));
    }

    @Test
    void sumsEveryChunk() {
        limits.seed(island, Map.of(WORLD, Map.of(BlockLimitService.chunkKey(0, 0), 2, BlockLimitService.chunkKey(3, -1), 3)));
        limits.addHopper(island, WORLD, 100, 100);
        assertEquals(6, limits.hoppers(island));
    }

    @Test
    void sumsEveryWorldWithoutMixingTheirChunks() {
        // The same chunk coordinates in two worlds are two different chunks.
        limits.seed(island, Map.of(WORLD, Map.of(BlockLimitService.chunkKey(0, 0), 2)));
        limits.seedWorld(island, NETHER, Map.of(BlockLimitService.chunkKey(0, 0), 3));
        assertEquals(5, limits.hoppers(island));

        limits.reconcile(island, NETHER, 0, 0, 1);
        assertEquals(3, limits.hoppers(island));
        limits.removeHopper(island, WORLD, 0, 0);
        assertEquals(2, limits.hoppers(island));
    }

    @Test
    void reconcileReplacesOnlyThatChunk() {
        limits.seed(island, Map.of(WORLD, Map.of(BlockLimitService.chunkKey(0, 0), 2, BlockLimitService.chunkKey(1, 0), 3)));
        // A piston pushed a hopper the events never saw: chunk (0,0) really holds 4.
        limits.reconcile(island, WORLD, 0, 0, 4);
        assertEquals(7, limits.hoppers(island));
        limits.reconcile(island, WORLD, 1, 0, 0);
        assertEquals(4, limits.hoppers(island));
    }

    @Test
    void neverGoesBelowZero() {
        limits.seed(island, Map.of());
        limits.removeHopper(island, WORLD, 0, 0);
        assertEquals(0, limits.hoppers(island));
        limits.addHopper(island, WORLD, 0, 0);
        assertEquals(1, limits.hoppers(island));
    }
}
