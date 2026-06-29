package com.astralrealms.skyblock.model;

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

    @Getter
    @Setter
    private ItemStackWrapper value;


    @Override
    public Class<ItemStackWrapper> type() {
        return ItemStackWrapper.class;
    }
}
