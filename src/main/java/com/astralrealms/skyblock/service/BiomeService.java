package com.astralrealms.skyblock.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
    /** Chunk coordinates examined per tick at most, generated or not. */
    private static final int MAX_SKIPS_PER_TICK = 256;
    /** How often an island may be repainted. Each pass resends every chunk to every viewer. */
    private static final long REPAINT_COOLDOWN_MILLIS = 60_000;
    /** Edge of a biome cell, in blocks: biomes are stored per 4×4×4 volume. */
    private static final int CELL = 4;
    /**
     * Ceiling on the covered radius, in chunks, so a misconfigured border cannot walk the whole
     * world. 160 chunks (2560 blocks) covers the largest shipped border (5000 across); a bigger one
     * is clamped with a warning rather than silently.
     */
    private static final int MAX_CHUNK_RADIUS = 160;

    private final AstralSkyblock plugin;
    // Islands with a repaint in flight, so a second /is biome cannot start a parallel pass.
    // When each island was last repainted here; forgotten when its world unloads.
    private final Map<UUID, Long> lastRepaint = new ConcurrentHashMap<>();
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

    /** Forgets an island's repaint cooldown. Called when its world unloads here. */
    public void forget(UUID islandId) {
        this.lastRepaint.remove(islandId);
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
        if (biome == null || !this.plugin.configuration().isBiomeAllowed(biome.getKey().toString())) {
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

        Long last = this.lastRepaint.get(island.uniqueId());
        long remaining = last == null ? 0 : last + REPAINT_COOLDOWN_MILLIS - System.currentTimeMillis();
        if (remaining > 0 && !player.hasPermission("skyblock.admin")) {
            ASMessages.BIOME_COOLDOWN.message(player, placeholders.registerDirect("cooldown", (remaining + 999) / 1000));
            return;
        }

        if (!this.repainting.add(island.uniqueId())) {
            ASMessages.BIOME_IN_PROGRESS.message(player, placeholders);
            return;
        }
        this.lastRepaint.put(island.uniqueId(), System.currentTimeMillis());

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

        // The next few generated chunks. Never-generated ones are skipped (nothing to repaint, and
        // generating them would carve terrain out of the void just to colour it), but only so many
        // per tick: most of a border box is void.
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        int examined = 0;
        while (pass.cursor < pass.chunks.size() && loads.size() < CHUNKS_PER_TICK && examined < MAX_SKIPS_PER_TICK) {
            long coordinate = pass.chunks.get(pass.cursor++);
            examined++;
            int chunkX = (int) (coordinate >> 32);
            int chunkZ = (int) coordinate;
            if (pass.world.isChunkGenerated(chunkX, chunkZ))
                // Loaded asynchronously first: World#setBiome on an unloaded chunk loads it
                // synchronously, stalling the tick. Paper completes this on the main thread.
                loads.add(pass.world.getChunkAtAsync(chunkX, chunkZ, false));
        }

        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, loadError) -> {
                    try {
                        int minHeight = pass.world.getMinHeight();
                        int maxHeight = pass.world.getMaxHeight();
                        for (CompletableFuture<Chunk> load : loads) {
                            Chunk chunk = load.getNow(null);
                            if (chunk == null)
                                continue;
                            int baseX = chunk.getX() << 4;
                            int baseZ = chunk.getZ() << 4;
                            for (int x = 0; x < 16; x += CELL)
                                for (int z = 0; z < 16; z += CELL)
                                    for (int y = minHeight; y < maxHeight; y += CELL)
                                        pass.world.setBiome(baseX + x, y, baseZ + z, pass.biome);

                            // Biome colours are baked into the client's chunk mesh, so the chunk has
                            // to be resent for the change to be visible without a relog.
                            pass.world.refreshChunk(chunk.getX(), chunk.getZ());
                        }
                        Bukkit.getScheduler().runTask(this.plugin, () -> repaint(pass, player, placeholders));
                    } catch (Exception exception) {
                        // Scheduling throws once the plugin is disabling (and a world unloaded under
                        // the pass throws here too); release the slot rather than leaving the island
                        // unable to be repainted for the rest of this server's life.
                        this.repainting.remove(pass.islandId);
                        this.plugin.getSLF4JLogger().warn("Biome repaint of island {} aborted: {}", pass.islandId, exception.getMessage());
                    }
                });
    }

    /**
     * The chunks covered by an island's border, as packed {@code (x << 32) | z} coordinates —
     * the same box {@link LevelService} scores.
     */
    private List<Long> chunkCoordinates(Island island, World world) {
        double size = this.plugin.upgrades()
                .value(island, UpgradeType.WORLDBORDER_SIZE, this.plugin.configuration().defaultWorldBorderSize());
        int radius = (int) Math.ceil(size / 2 / 16) + 1;
        if (radius > MAX_CHUNK_RADIUS) {
            this.plugin.getSLF4JLogger().warn("Island {} has a {}-block border, wider than the {} blocks scanned; the rest is ignored",
                    island.uniqueId(), (int) size, MAX_CHUNK_RADIUS * 32);
            radius = MAX_CHUNK_RADIUS;
        }

        int centerX = (int) Math.floor(island.centerX()) >> 4;
        int centerZ = (int) Math.floor(island.centerZ()) >> 4;

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
