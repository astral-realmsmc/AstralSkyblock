package com.astralrealms.skyblock.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.block.Block;

import com.astralrealms.skyblock.model.upgrade.UpgradeType;

/**
 * Counts the blocks an island is capped on — currently only hoppers, for
 * {@link UpgradeType#HOPPERS_LIMIT}.
 *
 * <p>Counting hoppers by scanning the island on every placement would be far too expensive, so the
 * count is held in memory instead: {@link LevelService}'s scan already walks every block inside the
 * border, so it tallies hoppers as it goes and {@link #seed(UUID, int) seeds} the count when it
 * finishes, and placements and breaks adjust it from there. That scan runs when the island world
 * loads here and again on the rescan timer, so any drift the event path misses — an explosion, a
 * piston, a WorldEdit paste — is reconciled within one rescan interval rather than accumulating.
 *
 * <p>The counts are per-server and only meaningful for islands hosted here, which is also the only
 * place hoppers can be placed. They are dropped when the island's world unloads.
 */
public class BlockLimitService {

    private final Map<UUID, AtomicInteger> hoppers = new ConcurrentHashMap<>();
    // Running scans, by island. Main thread only: chunks are captured, and hoppers placed and
    // broken, on the main thread.
    private final Map<UUID, ScanTracker> scans = new ConcurrentHashMap<>();

    /** Notes that a level scan of the island has started; see {@link #chunkCaptured}. */
    public void beginScan(UUID islandId) {
        this.scans.put(islandId, new ScanTracker());
    }

    /**
     * Notes that the running scan has taken its snapshot of a chunk. Hoppers placed or broken there
     * from now on are invisible to the scan's tally, so they are kept aside and added to it when it
     * seeds the count.
     */
    public void chunkCaptured(UUID islandId, int chunkX, int chunkZ) {
        ScanTracker scan = this.scans.get(islandId);
        if (scan != null)
            scan.captured.add(chunkKey(chunkX, chunkZ));
    }

    /** Forgets a scan that will never seed (it failed, or the world unloaded under it). */
    public void endScan(UUID islandId) {
        this.scans.remove(islandId);
    }

    /**
     * Replaces an island's hopper count with a freshly scanned one, corrected by the hoppers placed
     * or broken in chunks after the scan had already captured them. Called by the level scan, which
     * is the only thing that sees every block inside the border at once.
     */
    public void seed(UUID islandId, int count) {
        ScanTracker scan = this.scans.remove(islandId);
        int corrected = Math.max(0, count + (scan == null ? 0 : scan.delta));
        this.hoppers.computeIfAbsent(islandId, ignored -> new AtomicInteger()).set(corrected);
    }

    /** Forgets an island's counts. Called when its world unloads; the next scan seeds it again. */
    public void forget(UUID islandId) {
        this.hoppers.remove(islandId);
        this.scans.remove(islandId);
    }

    /**
     * Whether the island's hoppers have been counted since its world loaded. Until then the count
     * is unknown, and placements are refused rather than measured against zero — an island at its
     * cap could otherwise fill up again in the seconds after every load.
     */
    public boolean isCounted(UUID islandId) {
        return this.hoppers.containsKey(islandId);
    }

    /** The hoppers currently counted on an island; 0 when not counted yet (see {@link #isCounted}). */
    public int hoppers(UUID islandId) {
        AtomicInteger count = this.hoppers.get(islandId);
        return count == null ? 0 : count.get();
    }

    /**
     * Books one more hopper against the island's cap. Call this only once the placement is known to
     * have gone through — checking with {@link #hoppers(UUID)} and booking here are separate steps
     * so that a placement another listener cancels after the check does not leave the count reading
     * one too high. Both run inside the same event dispatch on the main thread, so nothing can slip
     * a second placement between them.
     */
    public void addHopper(UUID islandId, Block block) {
        adjust(islandId, block, 1);
    }

    /** Gives one hopper back to the island's cap. Never drops below zero. */
    public void removeHopper(UUID islandId, Block block) {
        adjust(islandId, block, -1);
    }

    private void adjust(UUID islandId, Block block, int delta) {
        ScanTracker scan = this.scans.get(islandId);
        if (scan != null && scan.captured.contains(chunkKey(block.getX() >> 4, block.getZ() >> 4)))
            scan.delta += delta;

        AtomicInteger count = this.hoppers.get(islandId);
        if (count == null)
            return; // not counted yet: the coming scan sees the block as it is
        count.updateAndGet(current -> Math.max(0, current + delta));
    }

    /** Drops every count. Called on plugin disable. */
    public void clear() {
        this.hoppers.clear();
        this.scans.clear();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static final class ScanTracker {
        private final Set<Long> captured = new HashSet<>();
        private int delta;
    }
}
