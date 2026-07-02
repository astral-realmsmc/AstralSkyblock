package com.astralrealms.skyblock.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.GeneratorConfiguration;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GeneratorListener implements Listener {

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFormEvent(BlockFormEvent e) {
        Island island = this.plugin.worlds()
                .findByWorld(e.getBlock().getWorld())
                .orElse(null);
        if (island == null)
            return;

        GeneratorConfiguration blueprint = this.plugin.generators().defaultGenerator(); // TODO: Replace that
        if (e.getNewState().getType() != Material.COBBLESTONE
            && e.getNewState().getType() != Material.BASALT)
            return;

        BlockData newBlock = blueprint.randomBlock();
        e.getBlock().setBlockData(newBlock, false);

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockFromToEvent(BlockFromToEvent e) {
        Block block = e.getBlock();
        if (!block.getType().equals(Material.LAVA))
            return;

        Island island = this.plugin.worlds()
                .findByWorld(block.getWorld())
                .orElse(null);
        if (island == null)
            return;

        // TODO: Fix that shit
        //GeneratorConfiguration blueprint = this.plugin.generators().defaultGenerator(); // TODO: Replace that
        //BlockData newBlock = blueprint.randomBlock();
        //e.getBlock().setBlockData(newBlock, false);

        //e.setCancelled(true);
    }
}
