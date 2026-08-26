package com.astralrealms.skyblock.listener;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;

import com.astralrealms.skyblock.configuration.GeneratorConfiguration;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

/**
 * Replaces the output of a cobble/stone generator with a roll from the island's configured
 * generator (its {@link com.astralrealms.skyblock.model.upgrade.UpgradeType#GENERATOR} level).
 *
 * <p>Every fluid-interaction block creation — flowing lava meeting water, lava flowing onto a water
 * source, basalt formation over soul soil — surfaces as a {@link BlockFormEvent} on Paper, so this
 * single handler covers all of them; there is no need to also intercept the fluid's
 * {@code BlockFromToEvent} spread, which fires for ordinary flow that creates nothing.
 *
 * <p>Obsidian is deliberately not intercepted: it is a lava-source interaction players farm on
 * purpose, not generator output.
 */
@RequiredArgsConstructor
public class GeneratorListener implements Listener {

    /** Vanilla generator outputs that a configured generator may replace. */
    private static final Set<Material> GENERATED = EnumSet.of(
            Material.COBBLESTONE, Material.STONE, Material.BASALT);

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!GENERATED.contains(event.getNewState().getType()))
            return;

        Island island = this.plugin.worlds()
                .findByWorld(event.getBlock().getWorld())
                .orElse(null);
        if (island == null)
            return;

        GeneratorConfiguration generator = this.plugin.upgrades().generator(island);
        if (generator == null)
            return;

        BlockData rolled = generator.randomBlock();
        if (rolled == null)
            return;

        // The forming block is replaced directly rather than through the event's new state, so the
        // roll survives whatever the vanilla logic intended to place.
        event.setCancelled(true);
        event.getBlock().setBlockData(rolled, false);
    }
}
