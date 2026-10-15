package com.astralrealms.skyblock.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;

import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;

import net.kyori.adventure.nbt.CompoundBinaryTag;

/**
 * Counts the blocks an island is capped on — currently only hoppers, for
 * {@link UpgradeType#HOPPERS_LIMIT}.
 *
 * <p>Counts are kept per chunk. They are {@link #seed seeded} from the island's block entities the
 * moment its world loads (hoppers are block entities, so that costs no block walk), adjusted by
 * placements and breaks, and each chunk is {@link #reconcile reconciled} whenever the level scan
 * captures it — which repairs whatever the event path missed (a piston, a WorldEdit paste) in that
 * chunk without a blind spot, since capture and reconciliation happen in the same main-thread step.
 *
 * <p>Counts are kept per world as well, since an island's dimensions share its chunk coordinates;
 * the cap applies to the island's worlds together.
 *
 * <p>The counts are per-server and only meaningful for islands hosted here, which is also the only
 * place hoppers can be placed. They are dropped when the island's world unloads.
 */
public class BlockLimitService {

    private static final String HOPPER_ID = "minecraft:hopper";

    // islandId -> world name -> chunk key -> hoppers in that chunk. Main thread only, like the
    // events feeding it.
    private final Map<UUID, Map<String, Map<Long, Integer>>> hoppers = new ConcurrentHashMap<>();

    /**
     * The hoppers of one world, by chunk, taken as the world comes up and before anyone can place
     * one. Stored chunks are read from their block entity data; chunks already loaded are counted
     * live instead, since their stored copy may be stale. Main thread.
     */
    public static Map<Long, Integer> census(SlimeWorldInstance instance) {
        World world = instance.getBukkitWorld();
        Map<Long, Integer> counts = new HashMap<>();
        for (SlimeChunk chunk : LevelService.storedChunks(instance)) {
            if (world.isChunkLoaded(chunk.getX(), chunk.getZ()))
                continue;
            int count = 0;
            for (CompoundBinaryTag tileEntity : chunk.getTileEntities())
                if (HOPPER_ID.equals(tileEntity.getString("id")))
                    count++;
            counts.put(chunkKey(chunk.getX(), chunk.getZ()), count);
        }
        for (Chunk chunk : world.getLoadedChunks())
            counts.put(chunkKey(chunk.getX(), chunk.getZ()), countHoppers(chunk));
        return counts;
    }

    /** The hoppers in a loaded chunk, from its block entities. */
    static int countHoppers(Chunk chunk) {
        return chunk.getTileEntities(block -> block.getType() == Material.HOPPER, false).size();
    }

    /** Replaces an island's counts with a freshly taken census of every one of its worlds, by world name then chunk. */
    public void seed(UUID islandId, Map<String, Map<Long, Integer>> perWorld) {
        Map<String, Map<Long, Integer>> worlds = new ConcurrentHashMap<>();
        perWorld.forEach((world, perChunk) -> worlds.put(world, positive(perChunk)));
        this.hoppers.put(islandId, worlds);
    }

    /**
     * Adds the census of a world that came up after the island was counted — a dimension entered
     * for the first time. Ignored until the island has been seeded: the census that seeds it will
     * see this world too.
     */
    public void seedWorld(UUID islandId, String world, Map<Long, Integer> perChunk) {
        Map<String, Map<Long, Integer>> worlds = this.hoppers.get(islandId);
        if (worlds != null)
            worlds.put(world, positive(perChunk));
    }

    private static Map<Long, Integer> positive(Map<Long, Integer> perChunk) {
        Map<Long, Integer> counts = new ConcurrentHashMap<>();
        perChunk.forEach((chunk, count) -> {
            if (count > 0)
                counts.put(chunk, count);
        });
        return counts;
    }

    /** Replaces one chunk's count with an exact one. Ignored until the island has been seeded. */
    public void reconcile(UUID islandId, String world, int chunkX, int chunkZ, int count) {
        Map<Long, Integer> counts = counts(islandId, world);
        if (counts == null)
            return;
        if (count > 0)
            counts.put(chunkKey(chunkX, chunkZ), count);
        else
            counts.remove(chunkKey(chunkX, chunkZ));
    }

    /** Forgets an island's counts. Called when its world unloads; the next load seeds it again. */
    public void forget(UUID islandId) {
        this.hoppers.remove(islandId);
    }

    /**
     * Whether the island's hoppers have been counted since its world loaded. Until then the count
     * is unknown, and placements are refused rather than measured against zero.
     */
    public boolean isCounted(UUID islandId) {
        return this.hoppers.containsKey(islandId);
    }

    /** The hoppers currently counted on an island; 0 when not counted yet (see {@link #isCounted}). */
    public int hoppers(UUID islandId) {
        Map<String, Map<Long, Integer>> worlds = this.hoppers.get(islandId);
        if (worlds == null)
            return 0;
        int total = 0;
        for (Map<Long, Integer> counts : worlds.values())
            for (int count : counts.values())
                total += count;
        return total;
    }

    /**
     * Books one more hopper against the island's cap. Call this only once the placement is known to
     * have gone through — checking with {@link #hoppers(UUID)} and booking here are separate steps
     * so that a placement another listener cancels after the check does not leave the count reading
     * one too high. Both run inside the same event dispatch on the main thread, so nothing can slip
     * a second placement between them.
     */
    public void addHopper(UUID islandId, String world, int blockX, int blockZ) {
        adjust(islandId, world, blockX, blockZ, 1);
    }

    /** Gives one hopper back to the island's cap. Never drops a chunk below zero. */
    public void removeHopper(UUID islandId, String world, int blockX, int blockZ) {
        adjust(islandId, world, blockX, blockZ, -1);
    }

    private void adjust(UUID islandId, String world, int blockX, int blockZ, int delta) {
        Map<Long, Integer> counts = counts(islandId, world);
        if (counts == null)
            return; // not counted yet: the coming census sees the block as it is
        counts.compute(chunkKey(blockX >> 4, blockZ >> 4), (chunk, current) -> {
            int next = (current == null ? 0 : current) + delta;
            return next > 0 ? next : null;
        });
    }

    /** One world's counts of a counted island, created empty for a world it did not have yet. */
    private Map<Long, Integer> counts(UUID islandId, String world) {
        Map<String, Map<Long, Integer>> worlds = this.hoppers.get(islandId);
        return worlds == null ? null : worlds.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
    }

    /** Drops every count. Called on plugin disable. */
    public void clear() {
        this.hoppers.clear();
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
