package com.astralrealms.skyblock.listener;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import com.astralrealms.core.paper.utils.ItemStackUtils;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.destroystokyo.paper.MaterialTags;

import io.papermc.paper.event.player.PlayerTradeEvent;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandPermissionsListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVillagerTrade(PlayerTradeEvent e) {
        cancelIfDisabled(e, e.getPlayer(), e.getVillager().getWorld(), IslandPermission.VILLAGER_TRADING);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        IslandPermission permission = IslandPermission.BREAK;
        if (block.getType().equals(Material.SPAWNER))
            permission = IslandPermission.SPAWNER_BREAK;
        // TODO: Valuables
        cancelIfDisabled(event, event.getPlayer(), block.getWorld(), permission);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getBlock().getWorld(), IslandPermission.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player)
            cancelIfDisabled(event, player, event.getEntity().getWorld(), IslandPermission.ANIMAL_BREED);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityShear(PlayerShearEntityEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.ANIMAL_SHEAR);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause().equals(PlayerTeleportEvent.TeleportCause.ENDER_PEARL))
            cancelIfDisabled(event, event.getPlayer(), event.getTo().getWorld(), IslandPermission.ENDER_PEARL);
        else if (event.getCause().equals(PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT))
            cancelIfDisabled(event, event.getPlayer(), event.getTo().getWorld(), IslandPermission.CHORUS_FRUIT);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItem(EntityDropItemEvent event) {
        if (event.getEntity() instanceof Player player)
            cancelIfDisabled(event, player, event.getItemDrop().getWorld(), IslandPermission.DROP_ITEMS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player)
            cancelIfDisabled(event, player, event.getItem().getWorld(), IslandPermission.PICKUP_DROPS);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player)
            cancelIfDisabled(event, player, event.getMount().getWorld(), IslandPermission.ENTITY_RIDE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSheepDye(PlayerInteractAtEntityEvent e) {
        Player player = e.getPlayer();
        ItemStack itemStack = player.getInventory().getItem(e.getHand());
        if (!ItemStackUtils.isAirOrNull(itemStack)
            && e.getRightClicked() instanceof Sheep sheep
            && MaterialTags.DYES.isTagged(itemStack.getType()))
            cancelIfDisabled(e, e.getPlayer(), sheep.getWorld(), IslandPermission.DYE_SHEEP);
    }

    private <E extends Event & Cancellable> boolean cancelIfDisabled(E event, Player player, World world, IslandPermission permission) {
        if (event.isCancelled())
            return true;

        Island island = this.plugin.worlds()
                .findByWorld(world)
                .orElse(null);
        if (island == null
            || island.hasPermission(player, permission))
            return false;

        event.setCancelled(true);
        return true;
    }
}
