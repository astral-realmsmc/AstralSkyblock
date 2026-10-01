package com.astralrealms.skyblock.listener;

import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.Nullable;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.astralrealms.core.paper.utils.ItemStackUtils;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.utils.SkyblockTags;
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
        Material type = block.getType();
        if (type == Material.SPAWNER || type == Material.TRIAL_SPAWNER || type == Material.VAULT)
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

    /** Q, or dragging an item out of the inventory — which fires this, not EntityDropItemEvent. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getItemDrop().getWorld(), IslandPermission.DROP_ITEMS);
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

    /**
     * Any block-backed inventory: chests (single and double), barrels, shulker boxes, furnaces,
     * hoppers, dispensers, droppers, brewing stands, lecterns, crafters... Ender chests are
     * per-player and stay open to everyone; minecarts have their own permission above.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if ((holder instanceof BlockInventoryHolder || holder instanceof DoubleChest)
            && event.getPlayer() instanceof Player player)
            cancelIfDisabled(event, player, player.getWorld(), IslandPermission.CONTAINER_OPEN);
    }

    /**
     * Doors, trapdoors, fence gates and redstone components. Only the block interaction is denied,
     * so the held item is still usable (e.g. eating while looking at a door).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null)
            return;

        Material type = block.getType();
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL) {
            // Stepping on pressure plates and tripwires; trampling farmland and turtle eggs.
            if (Tag.PRESSURE_PLATES.isTagged(type) || type == Material.TRIPWIRE)
                cancelIfDisabled(event, player, block.getWorld(), IslandPermission.USE_REDSTONE);
            else if (type == Material.FARMLAND || type == Material.TURTLE_EGG)
                cancelIfDisabled(event, player, block.getWorld(), IslandPermission.BREAK);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        // What the click does to the block itself...
        IslandPermission blockPermission = null;
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type))
            blockPermission = IslandPermission.USE_DOORS;
        else if (SkyblockTags.MECHANISMS.isTagged(type))
            blockPermission = IslandPermission.USE_REDSTONE;
        else if (Tag.ALL_SIGNS.isTagged(type))
            // Editing, dyeing and glowing the text.
            blockPermission = IslandPermission.BUILD;
        else if (SkyblockTags.INTERACTABLE.isTagged(type))
            blockPermission = IslandPermission.INTERACT_BLOCK;
        if (blockPermission != null && isDenied(player, block.getWorld(), blockPermission))
            event.setUseInteractedBlock(Event.Result.DENY);

        // ...and what the held item does to it: none of these fire a place or break event.
        ItemStack item = event.getItem();
        if (!ItemStackUtils.isAirOrNull(item) && reshapesBlock(item.getType(), type)
            && isDenied(player, block.getWorld(), IslandPermission.BUILD))
            event.setUseItemInHand(Event.Result.DENY);
    }

    /**
     * Whether right-clicking {@code block} with {@code item} changes the block without a place or
     * break event: stripping, tilling, flattening into a path, waxing or scraping copper, and
     * retyping a spawner with an egg.
     */
    private static boolean reshapesBlock(Material item, Material block) {
        if (MaterialTags.SPAWN_EGGS.isTagged(item))
            return block == Material.SPAWNER || block == Material.TRIAL_SPAWNER;
        return Tag.ITEMS_AXES.isTagged(item)
               || Tag.ITEMS_HOES.isTagged(item)
               || Tag.ITEMS_SHOVELS.isTagged(item)
               || item == Material.HONEYCOMB;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getBlock().getWorld(), IslandPermission.BUILD);
    }

    /** Sweet berries and glow berries: picked without breaking the bush or vine. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getHarvestedBlock().getWorld(), IslandPermission.BREAK);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getLectern().getWorld(), IslandPermission.CONTAINER_OPEN);
    }

    /** Lighting TNT, by hand or with a burning projectile: it blows up whatever is around it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Player player = playerBehind(event.getPrimingEntity());
        if (player != null)
            cancelIfDisabled(event, player, event.getBlock().getWorld(), IslandPermission.BREAK);
    }

    // =========================================================================
    //  Entities
    // =========================================================================

    /**
     * Hurting anything that is not a player (PvP is a setting) or a hostile mob (always fair game),
     * in melee, with a projectile or with TNT someone lit.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player || victim instanceof Enemy)
            return;
        Player player = playerBehind(event.getDamager());
        if (player != null)
            cancelIfDisabled(event, player, victim.getWorld(), protectionFor(victim));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = playerBehind(event.getAttacker());
        if (player != null)
            cancelIfDisabled(event, player, event.getVehicle().getWorld(), IslandPermission.ENTITY_DAMAGE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = playerBehind(event.getAttacker());
        if (player != null)
            cancelIfDisabled(event, player, event.getVehicle().getWorld(), IslandPermission.ENTITY_DAMAGE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = playerBehind(event.getRemover());
        if (player != null)
            cancelIfDisabled(event, player, event.getEntity().getWorld(), IslandPermission.DECORATION);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getRightClicked().getWorld(), IslandPermission.DECORATION);
    }

    /** Putting an item in a frame, taking it out or rotating it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame)
            cancelIfDisabled(event, event.getPlayer(), frame.getWorld(), IslandPermission.DECORATION);
    }

    /** Armor stands, boats, minecarts and end crystals. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null)
            cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.ENTITY_PLACE);
    }

    /** Item frames, paintings and leash knots. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null)
            cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.ENTITY_PLACE);
    }

    private static IslandPermission protectionFor(Entity victim) {
        return victim instanceof Hanging || victim instanceof ArmorStand
                ? IslandPermission.DECORATION
                : IslandPermission.ENTITY_DAMAGE;
    }

    /** The player responsible for {@code entity}: itself, a projectile's shooter, or whoever lit a TNT. */
    private static @Nullable Player playerBehind(@Nullable Entity entity) {
        if (entity instanceof Player player)
            return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player)
            return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player)
            return player;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getBlock().getWorld(), IslandPermission.BUCKET);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getBlock().getWorld(), IslandPermission.BUCKET);
    }

    /** Scooping up fish, axolotls and tadpoles. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        cancelIfDisabled(event, event.getPlayer(), event.getEntity().getWorld(), IslandPermission.BUCKET);
    }

    /**
     * The dye is applied by the second of the two packets a right-click on a sheep sends, so
     * cancelling the interact-at event (the first) never stopped it; this is the event for the dye.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent e) {
        if (e.getPlayer() != null)
            cancelIfDisabled(e, e.getPlayer(), e.getEntity().getWorld(), IslandPermission.DYE_SHEEP);
    }

    /**
     * Toggling flight is checked above, but a player already flying when they arrive (from their own
     * island, or another plugin's /fly) would otherwise keep flying where FLY is denied.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player.isFlying()
            && player.getGameMode() != GameMode.CREATIVE
            && player.getGameMode() != GameMode.SPECTATOR
            && isDenied(player, player.getWorld(), IslandPermission.FLY))
            player.setFlying(false);
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
        else if ((itemStack.getType() == Material.SADDLE
                  && (entity instanceof Steerable || entity instanceof AbstractHorse))
                 || itemStack.getType().name().endsWith("_HARNESS")) // happy ghast harnesses
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
                .isValuable(block.getType(), this.plugin.configuration().level().valuableThreshold());
    }

    private <E extends Event & Cancellable> boolean cancelIfDisabled(E event, Player player, World world, IslandPermission permission) {
        if (event.isCancelled())
            return true;
        if (!isDenied(player, world, permission))
            return false;

        event.setCancelled(true);
        return true;
    }

    /** Whether {@code world} is an island on which {@code player} lacks {@code permission}. */
    private boolean isDenied(Player player, World world, IslandPermission permission) {
        Island island = this.plugin.worlds()
                .findByWorld(world)
                .orElse(null);
        if (island != null)
            return !island.hasPermission(player, permission);

        // An island world whose island is not cached right now (evicted, or between an invalidation
        // and its reload): unknown permissions deny — allowing would switch protection off. Staff
        // still pass. The island is reloaded in the background so this only lasts a moment.
        UUID islandId = this.plugin.worlds().findIslandIdByWorld(world).orElse(null);
        if (islandId == null)
            return false;
        this.plugin.islands().repository().findById(islandId);
        return !player.hasPermission("skyblock.admin");
    }
}
