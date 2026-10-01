package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

class BlockLimitServiceTest {

    private final BlockLimitService limits = new BlockLimitService();
    private final UUID island = UUID.randomUUID();

    private static Block blockAt(int x, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    @Test
    void isUncountedUntilTheFirstScan() {
        assertFalse(limits.isCounted(island));
        limits.addHopper(island, blockAt(0, 0)); // the coming scan sees it
        limits.seed(island, 1);
        assertTrue(limits.isCounted(island));
        assertEquals(1, limits.hoppers(island));
    }

    @Test
    void keepsHoppersPlacedInChunksTheScanAlreadyCaptured() {
        limits.seed(island, 3);
        limits.beginScan(island);
        limits.chunkCaptured(island, 0, 0);

        // Placed after chunk (0,0) was captured: the scan's tally misses it.
        limits.addHopper(island, blockAt(5, 5));
        // Placed in a chunk not captured yet: the scan's tally will include it.
        limits.addHopper(island, blockAt(40, 40));

        limits.seed(island, 4); // the 3 existing + the one in the uncaptured chunk
        assertEquals(5, limits.hoppers(island));
    }

    @Test
    void neverGoesBelowZero() {
        limits.seed(island, 0);
        limits.removeHopper(island, blockAt(0, 0));
        assertEquals(0, limits.hoppers(island));
    }
}
