package com.astralrealms.skyblock.listener;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

import lombok.RequiredArgsConstructor;

/**
 * Applies the island upgrades whose effect is a decision taken at event time rather than a piece of
 * world state: the hopper and minecart caps ({@link UpgradeType#HOPPERS_LIMIT},
 * {@link UpgradeType#MINECART_LIMITS}) and the crop growth, spawner rate and mob drop multipliers
 * ({@link UpgradeType#CROP_GROWTH_SPEED}, {@link UpgradeType#SPAWNERS_RATE},
 * {@link UpgradeType#MOB_DROPS}).
 *
 * <p>The border upgrade is not here — it is world state, applied by
 * {@link com.astralrealms.skyblock.service.UpgradeService#applyWorldBorder}. The member and coop
 * caps are not here either: they are enforced where their rows are written, which is the only place
 * that can be authoritative across servers.
 *
 * <p>Registered on island servers only, since every event it handles happens inside an island world.
 */
@RequiredArgsConstructor
public class UpgradeEffectsListener implements Listener {

    private final AstralSkyblock plugin;

    // =========================================================================
    //  Hopper cap
    // =========================================================================

    /**
     * Refuses a hopper that would put the island over its cap. Runs after the permission listener's
     * {@code LOW} gate has had its say — a player who may not build there should be told that, not
     * that the island is full of hoppers.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHopperPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.HOPPER)
            return;

        Island island = island(event.getBlock().getWorld());
        if (island == null)
            return;

        int limit = this.plugin.upgrades().hopperLimit(island);
        if (this.plugin.blockLimits().hoppers(island.uniqueId()) < limit)
            return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ASMessages.HOPPER_LIMIT_REACHED.message(
                player,
                AstralPaperAPI.createPlaceholderContainer(player)
                        .registerPlaceholder(island)
                        .registerDirect("limit", limit));
    }

    /**
     * Books a placed hopper against the island's cap. Deliberately separate from the check above and
     * at {@code MONITOR}, so a placement that some other listener cancels in between is never
     * counted — the check would otherwise book a hopper that was never placed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPlaced(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.HOPPER)
            return;

        Island island = island(event.getBlock().getWorld());
        if (island != null)
            this.plugin.blockLimits().addHopper(island.uniqueId());
    }

    /** Gives a broken hopper's slot back to the island's cap. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.HOPPER)
            return;

        Island island = island(event.getBlock().getWorld());
        if (island != null)
            this.plugin.blockLimits().removeHopper(island.uniqueId());
    }

    /** Gives back the slots of hoppers destroyed by an entity's explosion. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        releaseHoppers(event.getLocation().getWorld(), event.blockList());
    }

    /** Gives back the slots of hoppers destroyed by a block's explosion (a bed, an anchor, ...). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        releaseHoppers(event.getBlock().getWorld(), event.blockList());
    }

    /**
     * Decrements the island's hopper count once per hopper in a destroyed block list. Without this
     * an explosion would leave the count reading high until the next level scan reconciled it, and
     * the island would refuse hoppers it has room for in the meantime.
     */
    private void releaseHoppers(World world, List<Block> destroyed) {
        Island island = island(world);
        if (island == null)
            return;

        for (Block block : destroyed)
            if (block.getType() == Material.HOPPER)
                this.plugin.blockLimits().removeHopper(island.uniqueId());
    }

    // =========================================================================
    //  Minecart cap
    // =========================================================================

    /**
     * Refuses a minecart that would put the island over its cap. Counted live from the world rather
     * than tracked, because a minecart is an entity: the world already knows how many there are, and
     * an island world holds few enough of them for the count to be cheap.
     *
     * <p>Placement is the gate, not creation: a minecart that already exists must survive its chunk
     * being unloaded and loaded again, which lowering the cap would otherwise quietly delete.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMinecartPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Minecart minecart))
            return;

        World world = minecart.getWorld();
        Island island = island(world);
        if (island == null)
            return;

        int limit = this.plugin.upgrades().minecartLimit(island);
        // The minecart being placed is already in the world when this fires, so it counts itself:
        // the cap is crossed at limit + 1, not at limit.
        if (world.getEntitiesByClass(Minecart.class).size() <= limit)
            return;

        event.setCancelled(true);

        // Null when a dispenser placed it, in which case there is nobody to tell.
        Player player = event.getPlayer();
        if (player != null)
            ASMessages.MINECART_LIMIT_REACHED.message(
                    player,
                    AstralPaperAPI.createPlaceholderContainer(player)
                            .registerPlaceholder(island)
                            .registerDirect("limit", limit));
    }

    // =========================================================================
    //  Crop growth
    // =========================================================================

    /**
     * Advances a growing crop further than vanilla would, by the island's crop growth multiplier.
     * The event's pending state is edited rather than the block, so the growth still counts as one
     * growth — it just lands further along.
     *
     * <p>Only crops that track an age are affected; bamboo, kelp and cactus grow by spreading, which
     * is a different event and has no age to advance.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        BlockState newState = event.getNewState();
        if (!(newState.getBlockData() instanceof Ageable ageable) || ageable.getAge() >= ageable.getMaximumAge())
            return;

        Island island = island(event.getBlock().getWorld());
        if (island == null)
            return;

        int extra = roll(this.plugin.upgrades().cropGrowthMultiplier(island) - 1);
        if (extra <= 0)
            return;

        ageable.setAge(Math.min(ageable.getMaximumAge(), ageable.getAge() + extra));
        newState.setBlockData(ageable);
    }

    // =========================================================================
    //  Spawner rate
    // =========================================================================

    /**
     * Shortens the delay a spawner arms after spawning, by the island's spawner rate multiplier.
     * The spawner picks its next delay itself after this event resolves, so the shortening is
     * applied a tick later — reading it here would only see the delay that just expired.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        if (spawner == null)
            return;

        Island island = island(spawner.getWorld());
        if (island == null)
            return;

        double multiplier = this.plugin.upgrades().spawnerRateMultiplier(island);
        if (multiplier <= 1)
            return;

        Block block = spawner.getBlock();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!(block.getState() instanceof CreatureSpawner armed))
                return; // broken between the spawn and now
            armed.setDelay((int) Math.max(1, armed.getDelay() / multiplier));
            armed.update(false, false);
        });
    }

    // =========================================================================
    //  Mob drops
    // =========================================================================

    /**
     * Multiplies what a mob drops by the island's mob drop multiplier. Stack sizes are raised in
     * place rather than by adding stacks, so a doubled drop is one stack of two rather than two of
     * one; a stack already at its maximum is left alone. Dropped experience is untouched.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player)
            return;

        Island island = island(event.getEntity().getWorld());
        if (island == null)
            return;

        double bonus = this.plugin.upgrades().mobDropsMultiplier(island) - 1;
        if (bonus <= 0)
            return;

        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getAmount() <= 0)
                continue;

            int extra = roll(drop.getAmount() * bonus);
            if (extra > 0)
                drop.setAmount(Math.min(drop.getMaxStackSize(), drop.getAmount() + extra));
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** The island whose world this is, or {@code null} when the world is not an island's. */
    private Island island(World world) {
        return this.plugin.worlds()
                .findByWorld(world)
                .orElse(null);
    }

    /**
     * Turns a fractional amount into a whole one: the integer part always, plus the remainder as a
     * chance. {@code 1.5} therefore yields 1 half the time and 2 the other half, which averages out
     * to the configured multiplier over many rolls rather than rounding every one of them the same
     * way.
     */
    private static int roll(double amount) {
        if (amount <= 0)
            return 0;

        int whole = (int) amount;
        double remainder = amount - whole;
        if (remainder > 0 && ThreadLocalRandom.current().nextDouble() < remainder)
            whole++;
        return whole;
    }
}
