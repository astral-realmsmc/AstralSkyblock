package com.astralrealms.skyblock.listener;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;

import com.astralrealms.core.paper.utils.ItemStackUtils;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.destroystokyo.paper.MaterialTags;

import io.papermc.paper.event.player.PlayerNameEntityEvent;
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
        else if (isValuable(block))
            permission = IslandPermission.VALUABLE_BREAK;
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
        if (!(event.getEntity() instanceof Player player))
            return;

        IslandPermission permission = event.getMount() instanceof Minecart
                ? IslandPermission.MINECART_ENTER
                : IslandPermission.ENTITY_RIDE;
        cancelIfDisabled(event, player, event.getMount().getWorld(), permission);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMinecartOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Minecart minecart
            && event.getPlayer() instanceof Player player)
            cancelIfDisabled(event, player, minecart.getWorld(), IslandPermission.MINECART_OPEN);
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

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack itemStack = player.getInventory().getItem(event.getHand());
        if (ItemStackUtils.isAirOrNull(itemStack))
            return;

        Entity entity = event.getRightClicked();
        if (entity instanceof Creeper
            && (itemStack.getType() == Material.FLINT_AND_STEEL || itemStack.getType() == Material.FIRE_CHARGE))
            cancelIfDisabled(event, player, entity.getWorld(), IslandPermission.IGNITE_CREEPER);
        else if (itemStack.getType() == Material.SADDLE
                 && (entity instanceof Steerable || entity instanceof AbstractHorse))
            cancelIfDisabled(event, player, entity.getWorld(), IslandPermission.SADDLE_ENTITY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBrush(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null)
            return;

        ItemStack itemStack = event.getItem();
        if (!ItemStackUtils.isAirOrNull(itemStack)
            && itemStack.getType() == Material.BRUSH
            && (block.getType() == Material.SUSPICIOUS_SAND || block.getType() == Material.SUSPICIOUS_GRAVEL))
            cancelIfDisabled(event, event.getPlayer(), block.getWorld(), IslandPermission.BRUSH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (event.getPlayer() != null)
            cancelIfDisabled(event, event.getPlayer(), event.getBlock().getWorld(), IslandPermission.FERTILIZE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.FISHING)
            cancelIfDisabled(event, event.getPlayer(), event.getPlayer().getWorld(), IslandPermission.FISH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (event.isFlying()
            && player.getGameMode() != GameMode.CREATIVE
            && player.getGameMode() != GameMode.SPECTATOR)
            cancelIfDisabled(event, player, player.getWorld(), IslandPermission.FLY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.LEASH);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onNameEntity(PlayerNameEntityEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.NAME_ENTITY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSculkReceive(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player)
            cancelIfDisabled(event, player, event.getBlock().getWorld(), IslandPermission.SCULK_SENSOR);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof WindCharge windCharge
            && windCharge.getShooter() instanceof Player player)
            cancelIfDisabled(event, player, windCharge.getWorld(), IslandPermission.WIND_CHARGE);
    }

    private boolean isValuable(Block block) {
        return this.plugin.blockValuesConfiguration()
                .blocks()
                .keySet()
                .stream()
                .anyMatch(predicate -> predicate.test(block));
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
