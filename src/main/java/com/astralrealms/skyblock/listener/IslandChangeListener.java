package com.astralrealms.skyblock.listener;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import com.astralrealms.skyblock.AstralSkyblock;

import lombok.RequiredArgsConstructor;

/**
 * Marks the chunks of an island whose blocks change, so the periodic rescan only reads those
 * chunks. Covers the ways blocks change without a player command; a change the events miss is
 * still picked up by the next full scan ({@code /is calc}, or the first rescan after a load).
 */
@RequiredArgsConstructor
public class IslandChangeListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlace) {
            // A bed or a door can straddle a chunk border.
            for (BlockState state : multiPlace.getReplacedBlockStates())
                mark(state.getLocation());
            return;
        }
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        mark(event.getBlock());
        markAll(event.getBlock().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        markAll(event.getLocation().getWorld(), event.blockList());
    }

    /** Generators (cobblestone, ores), concrete, ice, snow. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        markPiston(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        markPiston(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks())
            mark(state.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        mark(event.getBlock());
    }

    /** Endermen, falling blocks, players stripping logs or tilling. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        mark(event.getBlock());
    }

    /**
     * A piston changes its own block, every block it moves, and where each of them lands. Both
     * neighbours along the axis are marked, since what {@code getDirection()} means for a retraction
     * is not worth depending on.
     */
    private void markPiston(BlockPistonEvent event, Collection<Block> moved) {
        BlockFace direction = event.getDirection();
        markAlongAxis(event.getBlock(), direction);
        for (Block block : moved)
            markAlongAxis(block, direction);
    }

    private void markAlongAxis(Block block, BlockFace direction) {
        mark(block);
        mark(block.getRelative(direction));
        mark(block.getRelative(direction.getOppositeFace()));
    }

    private void markAll(World world, Collection<Block> blocks) {
        UUID islandId = this.plugin.worlds().findIslandIdByWorld(world).orElse(null);
        if (islandId == null)
            return;
        for (Block block : blocks)
            this.plugin.levels().markDirty(islandId, world.getName(), block.getX() >> 4, block.getZ() >> 4);
    }

    private void mark(Block block) {
        mark(block.getWorld(), block.getX(), block.getZ());
    }

    private void mark(Location location) {
        mark(location.getWorld(), location.getBlockX(), location.getBlockZ());
    }

    private void mark(World world, int blockX, int blockZ) {
        this.plugin.worlds()
                .findIslandIdByWorld(world)
                .ifPresent(islandId -> this.plugin.levels().markDirty(islandId, world.getName(), blockX >> 4, blockZ >> 4));
    }
}
