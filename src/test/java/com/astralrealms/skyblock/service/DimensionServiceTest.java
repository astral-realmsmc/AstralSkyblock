package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.skyblock.model.dimension.DimensionPortal;
import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

class DimensionServiceTest {

    private static final MinecraftLocation SPAWN = new MinecraftLocation(0.5, 100, 0.5, 0, 0, null);

    private static IslandDimension dimension(String id, DimensionPortal portal) {
        return new IslandDimension(id, null, World.Environment.NETHER, id + ".slime", null, SPAWN, portal, null, null);
    }

    private final List<IslandDimension> dimensions = List.of(
            dimension("nether", DimensionPortal.NETHER),
            dimension("end", DimensionPortal.END),
            dimension("mines", DimensionPortal.NONE));

    @Test
    void portalsLeadFromTheOverworldToTheirDimension() {
        assertEquals("nether", DimensionService.route(null, DimensionPortal.NETHER, dimensions));
        assertEquals("end", DimensionService.route(null, DimensionPortal.END, dimensions));
    }

    @Test
    void aDimensionsOwnPortalLeadsHome() {
        assertEquals(IslandDimension.OVERWORLD, DimensionService.route("nether", DimensionPortal.NETHER, dimensions));
        assertEquals(IslandDimension.OVERWORLD, DimensionService.route("end", DimensionPortal.END, dimensions));
    }

    @Test
    void anotherPortalInADimensionLeadsToItsOwnDimension() {
        assertEquals("end", DimensionService.route("nether", DimensionPortal.END, dimensions));
        assertEquals("nether", DimensionService.route("mines", DimensionPortal.NETHER, dimensions));
    }

    @Test
    void portalsNobodyDeclaresLeadNowhere() {
        List<IslandDimension> netherOnly = List.of(dimension("nether", DimensionPortal.NETHER));
        assertNull(DimensionService.route(null, DimensionPortal.END, netherOnly));
        assertNull(DimensionService.route(null, null, dimensions));
        assertNull(DimensionService.route(null, DimensionPortal.NONE, dimensions));
    }

    @Test
    void unlocksAccumulateWithTheLevel() {
        IslandUpgrade blueprint = new IslandUpgrade(UpgradeType.DIMENSIONS, null, null, Map.of(
                0, level(0, null),
                1, level(1, "nether"),
                2, level(2, "end")));

        assertEquals(Set.of(), DimensionService.unlockedBy(blueprint, 0));
        assertEquals(Set.of("nether"), DimensionService.unlockedBy(blueprint, 1));
        assertEquals(Set.of("nether", "end"), DimensionService.unlockedBy(blueprint, 2));
        assertEquals(Set.of("nether", "end"), DimensionService.unlockedBy(blueprint, 7));
        assertTrue(DimensionService.unlockedBy(null, 7).isEmpty());
    }

    private static IslandUpgrade.Level level(int level, String key) {
        return new IslandUpgrade.Level(level, 0, null, null, level, key, null);
    }

    @Test
    void validationKeepsOnlyUsableDimensions() {
        Map<String, IslandDimension> declared = new LinkedHashMap<>();
        declared.put("nether", dimension(null, DimensionPortal.NETHER));
        declared.put("overworld", dimension(null, DimensionPortal.NONE)); // reserved
        declared.put("Bad Id", dimension(null, DimensionPortal.NONE));
        declared.put("hell", dimension(null, DimensionPortal.NETHER)); // the nether portal is taken
        declared.put("missing", new IslandDimension(null, null, null, "missing.slime", null, SPAWN, null, null, null));
        declared.put("nospawn", new IslandDimension(null, null, null, "nospawn.slime", null, null, null, null, null));
        declared.put("end", dimension(null, DimensionPortal.END));

        List<String> warnings = new ArrayList<>();
        Map<String, IslandDimension> valid = DimensionService.validate(declared,
                source -> !source.equals("missing.slime"), warnings::add);

        assertEquals(List.of("nether", "end"), List.copyOf(valid.keySet()));
        assertEquals("nether", valid.get("nether").id());
        assertEquals(5, warnings.size());
    }
}
