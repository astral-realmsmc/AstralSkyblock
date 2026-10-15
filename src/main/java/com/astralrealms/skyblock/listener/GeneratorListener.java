package com.astralrealms.skyblock.listener;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
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
 * generator (its {@link com.astralrealms.skyblock.model.upgrade.UpgradeType#GENERATOR} level). In a
 * dimension that overrides the generator at that level, the override is rolled instead.
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

    /**
     * Vanilla generator outputs that a configured generator may replace. Basalt is not among them:
     * generators are overworld mixes (cobble, ores, logs), and a soul soil + blue ice basalt
     * generator turning out oak logs made no sense.
     */
    private static final Set<Material> GENERATED = EnumSet.of(Material.COBBLESTONE, Material.STONE);
    /**
     * Outputs replaced in a dimension whose own generator is in effect. Basalt is the only generator
     * a nether can run — water evaporates there — so a nether generator rolls in its place.
     */
    private static final Set<Material> DIMENSION_GENERATED = EnumSet.of(Material.COBBLESTONE, Material.STONE, Material.BASALT);

    private final AstralSkyblock plugin;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Material formed = event.getNewState().getType();
        if (!DIMENSION_GENERATED.contains(formed))
            return;

        World world = event.getBlock().getWorld();
        Island island = this.plugin.worlds()
                .findByWorld(world)
                .orElse(null);
        if (island == null)
            return;

        String dimension = this.plugin.worlds().findDimension(world).orElse(null);
        GeneratorConfiguration override = this.plugin.upgrades().dimensionGenerator(island, dimension);
        if (override == null && !GENERATED.contains(formed))
            return;

        GeneratorConfiguration generator = override != null ? override : this.plugin.upgrades().generator(island);
        if (generator == null)
            return;

        BlockData rolled = generator.randomBlock();
        if (rolled == null)
            return;

        // The forming block is replaced directly rather than through the event's new state, so the
        // roll survives whatever the vanilla logic intended to place.
        event.setCancelled(true);
        // Physics only for gravity blocks: rolled sand or gravel should fall, not float.
        event.getBlock().setBlockData(rolled, rolled.getMaterial().hasGravity());
    }
}
