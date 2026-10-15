package com.astralrealms.skyblock.model.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class IslandWorldsTest {

    private final UUID island = UUID.randomUUID();

    @Test
    void namesTheOverworldAfterTheIslandAlone() {
        assertEquals(island.toString(), IslandWorlds.worldName(island, null));
        assertEquals(island + "_nether", IslandWorlds.worldName(island, "nether"));
    }

    @Test
    void parsesBothKindsOfName() {
        IslandWorlds.Name overworld = IslandWorlds.parse(island.toString()).orElseThrow();
        assertEquals(island, overworld.islandId());
        assertNull(overworld.dimension());

        IslandWorlds.Name nether = IslandWorlds.parse(island + "_nether").orElseThrow();
        assertEquals(island, nether.islandId());
        assertEquals("nether", nether.dimension());
    }

    @Test
    void rejectsOtherWorlds() {
        assertTrue(IslandWorlds.parse("world").isEmpty());
        assertTrue(IslandWorlds.parse("world_nether").isEmpty());
        assertTrue(IslandWorlds.parse(island + "_").isEmpty());
        assertTrue(IslandWorlds.parse(island + "-nether").isEmpty());
    }

    @Test
    void belongsOnlyToItsOwnIsland() {
        assertTrue(IslandWorlds.belongsTo(island.toString(), island));
        assertTrue(IslandWorlds.belongsTo(island + "_end", island));
        assertFalse(IslandWorlds.belongsTo(UUID.randomUUID() + "_end", island));
        assertFalse(IslandWorlds.belongsTo(island + "end", island));
    }
}
