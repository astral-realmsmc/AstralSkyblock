package com.astralrealms.skyblock.model.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class IslandDimensionTest {

    @Test
    void anOverrideAppliesUntilTheNextOne() {
        Map<Integer, String> generators = Map.of(1, "nether", 3, "nether-rich");

        assertNull(IslandDimension.generatorAt(generators, 0)); // the island's own generator
        assertEquals("nether", IslandDimension.generatorAt(generators, 1));
        assertEquals("nether", IslandDimension.generatorAt(generators, 2));
        assertEquals("nether-rich", IslandDimension.generatorAt(generators, 3));
        assertEquals("nether-rich", IslandDimension.generatorAt(generators, 10));
    }

    @Test
    void noOverridesMeansTheIslandsGenerator() {
        assertNull(IslandDimension.generatorAt(null, 5));
        assertNull(IslandDimension.generatorAt(Map.of(), 5));
    }

    @Test
    void defaultsFollowTheId() {
        IslandDimension dimension = new IslandDimension(null, null, null, "nether.slime", null, null, null, null, null).withId("nether");
        assertEquals("nether", dimension.displayName());
        assertEquals("skyblock.dimension.nether", dimension.permission());
        assertEquals(DimensionPortal.NONE, dimension.portal());
        assertEquals("normal", dimension.slimeEnvironment());
    }
}
