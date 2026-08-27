package com.astralrealms.skyblock.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

/**
 * Repaints the biome of an island world inside its border.
 *
 * <p>Biomes are stored per 4×4×4 cell, so a repaint writes one cell per 4 blocks rather than one per
 * block — but even then a default-sized island is tens of thousands of writes, and a large one far
 * more. The work is therefore spread across ticks a chunk at a time, in the same shape as
 * {@link LevelService}'s scan, and each touched chunk is resent to the players who can see it so the
 * change shows up without a relog.
 */
public class BiomeService {

    /** Chunks repainted per tick. Each is a few thousand cell writes plus one chunk resend. */
    private static final int CHUNKS_PER_TICK = 2;
    /** Edge of a biome cell, in blocks: biomes are stored per 4×4×4 volume. */
    private static final int CELL = 4;
    /** Hard ceiling on the repainted radius, mirroring the level scan's guard on a bad border. */
    private static final int MAX_CHUNK_RADIUS = 64;

    private final AstralSkyblock plugin;
    // Islands with a repaint in flight, so a second /is biome cannot start a parallel pass.
    private final Set<UUID> repainting = ConcurrentHashMap.newKeySet();

    public BiomeService(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolves a player-supplied biome name — {@code plains}, {@code minecraft:plains} or
     * {@code PLAINS} — to a registered biome, or {@code null} when nothing matches.
     */
    public @Nullable Biome resolve(String name) {
        if (name == null || name.isBlank())
            return null;

        NamespacedKey key = NamespacedKey.fromString(name.strip().toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.BIOME.get(key);
    }

    /** Every registered biome key, for command completion. */
    public List<String> biomeNames() {
        List<String> names = new ArrayList<>();
        Registry.BIOME.forEach(biome -> names.add(biome.getKey().getKey()));
        return names;
    }

    /**
     * Repaints an island's biome. Requires {@link IslandPermission#SET_BIOME}, and the island world
     * must be hosted on this server — only its host can write its blocks.
     */
    public void setBiome(Player player, Island island, String biomeName) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island)
                .registerDirect("biome", biomeName);

        if (!island.hasPermission(player, IslandPermission.SET_BIOME)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        Biome biome = resolve(biomeName);
        if (biome == null) {
            ASMessages.BIOME_UNKNOWN.message(player, placeholders);
            return;
        }

        World world = this.plugin.worlds()
                .findByIslandId(island.uniqueId())
                .map(instance -> instance.getBukkitWorld())
                .orElse(null);
        if (world == null) {
            ASMessages.BIOME_NOT_HOSTED.message(player, placeholders);
            return;
        }

        if (!this.repainting.add(island.uniqueId())) {
            ASMessages.BIOME_IN_PROGRESS.message(player, placeholders);
            return;
        }

        ASMessages.BIOME_UPDATING.message(player, placeholders);
        repaint(new Repaint(island.uniqueId(), world, biome, chunkCoordinates(island, world)), player, placeholders);
    }

    /**
     * Repaints the next few chunks and schedules itself for the following tick, until the pass runs
     * out of chunks or the world goes away under it. Main thread only — this writes blocks.
     */
    private void repaint(Repaint pass, Player player, PlaceholderContainer placeholders) {
        // The idle sweep (or a delete) can unload the world mid-pass; stop rather than write into a
        // world that is no longer there. What was already painted stays painted.
        if (Bukkit.getWorld(pass.world.getName()) == null) {
            this.repainting.remove(pass.islandId);
            if (player.isOnline())
                ASMessages.BIOME_NOT_HOSTED.message(player, placeholders);
            return;
        }

        if (pass.cursor >= pass.chunks.size()) {
            this.repainting.remove(pass.islandId);
            if (player.isOnline())
                ASMessages.BIOME_UPDATED.message(player, placeholders);
            return;
        }

        int minHeight = pass.world.getMinHeight();
        int maxHeight = pass.world.getMaxHeight();
        int end = Math.min(pass.cursor + CHUNKS_PER_TICK, pass.chunks.size());
        for (int index = pass.cursor; index < end; index++) {
            long coordinate = pass.chunks.get(index);
            int chunkX = (int) (coordinate >> 32);
            int chunkZ = (int) coordinate;
            // Never generated: nothing to repaint, and generating it here would carve terrain out of
            // the void just to colour it.
            if (!pass.world.isChunkGenerated(chunkX, chunkZ))
                continue;

            for (int x = 0; x < 16; x += CELL)
                for (int z = 0; z < 16; z += CELL)
                    for (int y = minHeight; y < maxHeight; y += CELL)
                        pass.world.setBiome((chunkX << 4) + x, y, (chunkZ << 4) + z, pass.biome);

            // Biome colours are baked into the client's chunk mesh, so the chunk has to be resent
            // for the change to be visible without a relog.
            pass.world.refreshChunk(chunkX, chunkZ);
        }
        pass.cursor = end;

        try {
            Bukkit.getScheduler().runTask(this.plugin, () -> repaint(pass, player, placeholders));
        } catch (Exception exception) {
            // Scheduling throws once the plugin is disabling; release the slot rather than leaving
            // the island unable to be repainted for the rest of this server's life.
            this.repainting.remove(pass.islandId);
            this.plugin.getSLF4JLogger().warn("Biome repaint of island {} aborted: {}", pass.islandId, exception.getMessage());
        }
    }

    /**
     * The chunks covered by an island's border, as packed {@code (x << 32) | z} coordinates —
     * the same box {@link LevelService} scores.
     */
    private List<Long> chunkCoordinates(Island island, World world) {
        double size = this.plugin.upgrades()
                .value(island, UpgradeType.WORLDBORDER_SIZE, this.plugin.configuration().defaultWorldBorderSize());
        int radius = Math.min(MAX_CHUNK_RADIUS, (int) Math.ceil(size / 2 / 16) + 1);

        int centerX = (int) Math.floor(island.spawnX()) >> 4;
        int centerZ = (int) Math.floor(island.spawnZ()) >> 4;

        List<Long> coordinates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++)
            for (int z = centerZ - radius; z <= centerZ + radius; z++)
                coordinates.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        return coordinates;
    }

    /** State of one repaint as it walks its chunks. */
    private static final class Repaint {
        private final UUID islandId;
        private final World world;
        private final Biome biome;
        private final List<Long> chunks;
        private int cursor;

        private Repaint(UUID islandId, World world, Biome biome, List<Long> chunks) {
            this.islandId = islandId;
            this.world = world;
            this.biome = biome;
            this.chunks = chunks;
        }
    }
}
