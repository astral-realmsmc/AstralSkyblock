package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IslandScoreTest {

    private final IslandScore score = new IslandScore();

    @Test
    void hasNoBaselineUntilAFullScan() {
        score.apply(Map.of(1L, 10L), false);
        assertFalse(score.hasBaseline());
        score.apply(Map.of(1L, 10L), true);
        assertTrue(score.hasBaseline());
    }

    @Test
    void partialScanOnlyOverwritesTheChunksItRead() {
        score.apply(Map.of(1L, 10L, 2L, 20L), true);
        score.apply(Map.of(2L, 5L), false);
        assertEquals(15, score.total());
    }

    @Test
    void fullScanDropsChunksItNoLongerSees() {
        score.apply(Map.of(1L, 10L, 2L, 20L), true);
        score.apply(Map.of(1L, 10L), true);
        assertEquals(10, score.total());
    }

    @Test
    void capturedChunksAreNoLongerDirtyUntilTheyChangeAgain() {
        score.markDirty(1L);
        score.markDirty(2L);
        score.captured(1L);
        assertEquals(Set.of(2L), score.dirtyChunks());

        score.restoreDirty(List.of(1L)); // the scan failed
        assertEquals(Set.of(1L, 2L), score.dirtyChunks());
    }

    @Test
    void knownChunksIncludeDirtyOnes() {
        score.apply(Map.of(1L, 10L), true);
        score.markDirty(7L);
        assertEquals(Set.of(1L, 7L), score.knownChunks());
    }

    @Test
    void borderBoxCoversTheRadiusAroundTheCentre() {
        LevelService.BorderBox box = new LevelService.BorderBox(0, 0, 2);
        assertTrue(box.contains(LevelService.chunkKey(2, -2)));
        assertFalse(box.contains(LevelService.chunkKey(3, 0)));
        assertFalse(box.contains(LevelService.chunkKey(0, -3)));
    }
}
