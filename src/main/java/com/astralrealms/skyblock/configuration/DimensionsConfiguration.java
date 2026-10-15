package com.astralrealms.skyblock.configuration;

import java.util.Map;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.skyblock.model.dimension.IslandDimension;

/**
 * {@code dimensions.yml}. Off unless {@link #enabled}. It must read the same on every island server:
 * any of them may host any island, and with it its dimensions.
 *
 * @param portalDelayTicks ticks a survival player stands in a nether portal before it takes them
 * @param portalCooldownTicks ticks after a portal trip during which portals are ignored
 */
@ConfigSerializable
public record DimensionsConfiguration(boolean enabled, int portalDelayTicks, int portalCooldownTicks,
                                      Map<String, IslandDimension> dimensions) {

    public static final DimensionsConfiguration DISABLED = new DimensionsConfiguration(false, 0, 0, Map.of());

    @Override
    public int portalDelayTicks() {
        return this.portalDelayTicks <= 0 ? 80 : this.portalDelayTicks;
    }

    @Override
    public int portalCooldownTicks() {
        return this.portalCooldownTicks <= 0 ? 40 : this.portalCooldownTicks;
    }

    @Override
    public Map<String, IslandDimension> dimensions() {
        return this.dimensions == null ? Map.of() : this.dimensions;
    }
}
