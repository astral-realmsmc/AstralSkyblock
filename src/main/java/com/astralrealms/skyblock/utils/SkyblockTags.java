package com.astralrealms.skyblock.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import com.destroystokyo.paper.MaterialSetTag;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SkyblockTags {

    public static final MaterialSetTag CONTAINERS = new MaterialSetTag(new NamespacedKey("paper", "containers_settag"))
            .endsWith("_CHEST")
            .contains("FURNACE")
            .contains("SHULKER_BOX")
            .contains("HOPPER")
            .add(Material.CHEST, Material.BARREL, Material.SMOKER, Material.CHEST_MINECART, Material.HOPPER_MINECART, Material.FURNACE_MINECART)
            .lock();
    public static final MaterialSetTag MECHANISMS = new MaterialSetTag(new NamespacedKey("paper", "mechanisms_settag"))
            .add(Material.LEVER, Material.DROPPER, Material.DISPENSER, Material.REPEATER, Material.COMPARATOR)
            .endsWith("_BUTTON")
            .endsWith("_PRESSURE_PLATE")
            .lock();
    public static final MaterialSetTag SPAWNERS = new MaterialSetTag(new NamespacedKey("paper", "spawners_settag"))
            .endsWith("_SPAWNER")
            .lock();
    public static final MaterialSetTag CROPS = new MaterialSetTag(new NamespacedKey("paper", "crops_settag"))
            .add(MaterialSetTag.CROPS)
            .add(Material.BAMBOO, Material.BAMBOO_SAPLING, Material.SUGAR_CANE, Material.KELP, Material.NETHER_WART, Material.CACTUS, Material.MELON_STEM, Material.PUMPKIN_STEM, Material.COCOA)
            .lock();
    public static final MaterialSetTag HARVESTABLE = new MaterialSetTag(new NamespacedKey("paper", "harvestable_settag"))
            .add(Material.SWEET_BERRY_BUSH, Material.GLOW_BERRIES, Material.SWEET_BERRIES, Material.CAVE_VINES)
            .lock();

}
