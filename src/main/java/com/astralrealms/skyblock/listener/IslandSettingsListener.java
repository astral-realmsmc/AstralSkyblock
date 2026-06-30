package com.astralrealms.skyblock.listener;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

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
    public void onFireSpread(BlockSpreadEvent e) {
        if (e.getNewState().getType() == Material.FIRE)
            cancelIfDisabled(e, e.getSource().getWorld(), IslandSettings.FIRE_SPREAD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { // PreEntityExplodeEvent
        IslandSettings settings = switch (e.getEntityType()) {
            case CREEPER -> IslandSettings.CREEPER_EXPLOSION;
            case WITHER -> IslandSettings.WITHER_EXPLOSION;
            case TNT -> IslandSettings.TNT_EXPLOSION;
            default -> null;
        };
        if (settings != null)
            cancelIfDisabled(e, e.getEntity().getWorld(), settings);
    }

    private <E extends Event & Cancellable> boolean cancelIfDisabled(E event, World world, IslandSettings settings) {
        if (event.isCancelled())
            return true;

        UUID islandId;
        try {
            islandId = UUID.fromString(world.getName());
        } catch (Exception e) {
            return false;
        }

        Island island = this.plugin.islands()
                .repository()
                .findCachedById(islandId)
                .orElse(null);
        if (island == null)
            return false;

        if (island.isSettingEnabled(settings))
            return false;

        event.setCancelled(true);
        return true;
    }
}
