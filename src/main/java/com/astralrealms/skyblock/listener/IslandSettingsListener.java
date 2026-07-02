package com.astralrealms.skyblock.listener;

import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.TimeSkipEvent;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.utils.SkyblockTags;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandSettingsListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        Block block = e.getBlock();

        // Lava flow
        if (block.getType().equals(Material.LAVA))
            cancelIfDisabled(e, block.getWorld(), IslandSettings.LAVA_FLOW);

        // Water flow
        if (block.getType().equals(Material.WATER))
            cancelIfDisabled(e, block.getWorld(), IslandSettings.WATER_FLOW);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCropGrowth(BlockGrowEvent e) {
        if (SkyblockTags.CROPS.isTagged(e.getBlock()))
            cancelIfDisabled(e, e.getBlock().getWorld(), IslandSettings.CROPS_GROWTH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        World world = e.getEntity().getWorld();
        if (e.getDamageSource().getCausingEntity() instanceof Player
            && e.getEntity() instanceof Player)
            cancelIfDisabled(e, world, IslandSettings.PVP);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        Material newType = e.getNewState().getType();
        if (newType == Material.FIRE)
            cancelIfDisabled(e, e.getSource().getWorld(), IslandSettings.FIRE_SPREAD);
        else if (SkyblockTags.CROPS.isTagged(newType)) // bamboo, kelp & co spread instead of growing
            cancelIfDisabled(e, e.getSource().getWorld(), IslandSettings.CROPS_GROWTH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        cancelIfDisabled(e, e.getBlock().getWorld(), IslandSettings.FIRE_SPREAD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        cancelIfDisabled(e, e.getWorld(), IslandSettings.TREE_GROWTH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEggLay(EntityDropItemEvent e) {
        if (e.getEntity() instanceof Chicken
            && e.getItemDrop().getItemStack().getType() == Material.EGG)
            cancelIfDisabled(e, e.getEntity().getWorld(), IslandSettings.EGG_LAY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEndermanGrief(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Enderman)
            cancelIfDisabled(e, e.getBlock().getWorld(), IslandSettings.ENDERMAN_GRIEF);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        IslandSettings settings = switch (e.getEntityType()) {
            case CREEPER -> IslandSettings.CREEPER_EXPLOSION;
            case WITHER, WITHER_SKULL -> IslandSettings.WITHER_EXPLOSION;
            case TNT, TNT_MINECART -> IslandSettings.TNT_EXPLOSION;
            case FIREBALL -> e.getEntity() instanceof Fireball fireball
                             && fireball.getShooter() instanceof Ghast
                    ? IslandSettings.GHAST_FIREBALL
                    : null;
            default -> null;
        };
        if (settings != null)
            cancelIfDisabled(e, e.getEntity().getWorld(), settings);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent e) {
        Island island = this.plugin.worlds()
                .findByWorld(e.getWorld())
                .orElse(null);
        if (island != null && fixedTime(island) != null)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent e) {
        Island island = this.plugin.worlds()
                .findByWorld(e.getWorld())
                .orElse(null);
        if (island == null)
            return;

        // ALWAYS_RAIN wins over ALWAYS_SHINY when both are enabled (see applyEnvironment)
        boolean alwaysRain = island.isSettingEnabled(IslandSettings.ALWAYS_RAIN);
        if (e.toWeatherState()
                ? island.isSettingEnabled(IslandSettings.ALWAYS_SHINY) && !alwaysRain
                : alwaysRain)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent e) {
        Island island = this.plugin.worlds()
                .findByWorld(e.getWorld())
                .orElse(null);
        if (island != null
            && e.toThunderState()
            && island.isSettingEnabled(IslandSettings.ALWAYS_SHINY)
            && !island.isSettingEnabled(IslandSettings.ALWAYS_RAIN))
            e.setCancelled(true);
    }

    /**
     * Applies the environmental settings (time & weather locks) to the island world. Must be called
     * on the main thread — from the world load path and again whenever settings are flushed, since
     * gamerules and world state cannot be driven by events alone.
     */
    public static void applyEnvironment(Island island, World world) {
        // Time
        Long time = fixedTime(island);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, time == null);
        if (time != null)
            world.setTime(time);

        // Weather
        boolean alwaysRain = island.isSettingEnabled(IslandSettings.ALWAYS_RAIN);
        boolean alwaysShiny = island.isSettingEnabled(IslandSettings.ALWAYS_SHINY);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, !alwaysRain && !alwaysShiny);
        if (alwaysRain) {
            world.setStorm(true);
        } else if (alwaysShiny) {
            world.setThundering(false);
            world.setStorm(false);
        }
    }

    private static Long fixedTime(Island island) {
        if (island.isSettingEnabled(IslandSettings.ALWAYS_DAY))
            return 0L;
        if (island.isSettingEnabled(IslandSettings.ALWAYS_MIDDLE_DAY))
            return 6000L;
        if (island.isSettingEnabled(IslandSettings.ALWAYS_NIGHT))
            return 14000L;
        if (island.isSettingEnabled(IslandSettings.ALWAYS_MIDDLE_NIGHT))
            return 18000L;
        return null;
    }

    private <E extends Event & Cancellable> boolean cancelIfDisabled(E event, World world, IslandSettings settings) {
        if (event.isCancelled())
            return true;

        Island island = this.plugin.worlds()
                .findByWorld(world)
                .orElse(null);
        if (island == null
            || island.isSettingEnabled(settings))
            return false;

        event.setCancelled(true);
        return true;
    }
}
