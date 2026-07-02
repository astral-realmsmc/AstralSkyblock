package com.astralrealms.skyblock.model.island;

import java.util.EnumSet;

import com.astralrealms.core.configuration.ConfigurationEnum;
import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;

import lombok.Getter;
import lombok.Setter;

public enum IslandSettings implements ConfigurationEnum<ItemStackWrapper> {
    ALWAYS_DAY,
    ALWAYS_MIDDLE_DAY,
    ALWAYS_NIGHT,
    ALWAYS_MIDDLE_NIGHT,
    ALWAYS_RAIN,
    ALWAYS_SHINY,
    CREEPER_EXPLOSION,
    CROPS_GROWTH,
    EGG_LAY,
    ENDERMAN_GRIEF,
    FIRE_SPREAD,
    GHAST_FIREBALL,
    LAVA_FLOW,
    PVP,
    TNT_EXPLOSION,
    TREE_GROWTH,
    WATER_FLOW,
    WITHER_EXPLOSION;

    private static final EnumSet<IslandSettings> TIME_LOCKS = EnumSet.of(ALWAYS_DAY, ALWAYS_MIDDLE_DAY, ALWAYS_NIGHT, ALWAYS_MIDDLE_NIGHT);
    private static final EnumSet<IslandSettings> WEATHER_LOCKS = EnumSet.of(ALWAYS_RAIN, ALWAYS_SHINY);

    @Getter
    @Setter
    private ItemStackWrapper value;

    /**
     * Settings that cannot be enabled at the same time as this one: an island can only have a
     * single time lock and a single weather lock active.
     */
    public EnumSet<IslandSettings> conflictingSettings() {
        EnumSet<IslandSettings> group = TIME_LOCKS.contains(this) ? TIME_LOCKS
                : WEATHER_LOCKS.contains(this) ? WEATHER_LOCKS
                : null;
        if (group == null)
            return EnumSet.noneOf(IslandSettings.class);

        EnumSet<IslandSettings> conflicting = EnumSet.copyOf(group);
        conflicting.remove(this);
        return conflicting;
    }

    @Override
    public Class<ItemStackWrapper> type() {
        return ItemStackWrapper.class;
    }
}
