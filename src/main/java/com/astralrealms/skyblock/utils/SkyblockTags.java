package com.astralrealms.skyblock.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

import com.destroystokyo.paper.MaterialSetTag;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SkyblockTags {

    public static final MaterialSetTag MECHANISMS = new MaterialSetTag(new NamespacedKey("paper", "mechanisms_settag"))
            .add(Material.LEVER, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR, Material.NOTE_BLOCK)
            .endsWith("_BUTTON")
            .endsWith("_PRESSURE_PLATE")
            .lock();
    public static final MaterialSetTag CROPS = new MaterialSetTag(new NamespacedKey("paper", "crops_settag"))
            .add(MaterialSetTag.CROPS)
            .add(Material.BAMBOO, Material.BAMBOO_SAPLING, Material.SUGAR_CANE, Material.KELP, Material.NETHER_WART, Material.CACTUS, Material.MELON_STEM, Material.PUMPKIN_STEM, Material.COCOA)
            .lock();
    /** Blocks whose right-click use changes them or someone's state, gated by INTERACT_BLOCK. */
    public static final MaterialSetTag INTERACTABLE = new MaterialSetTag(new NamespacedKey("paper", "interactable_settag"))
            .add(Tag.BEDS, Tag.FLOWER_POTS, Tag.CANDLE_CAKES, Tag.CAMPFIRES, Tag.CANDLES, Tag.CAULDRONS)
            .add(Material.CAKE, Material.COMPOSTER, Material.CHISELED_BOOKSHELF, Material.JUKEBOX,
                    Material.RESPAWN_ANCHOR, Material.DECORATED_POT, Material.BEEHIVE, Material.BEE_NEST)
            .lock();

}
