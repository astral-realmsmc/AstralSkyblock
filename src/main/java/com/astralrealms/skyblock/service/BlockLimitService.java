package com.astralrealms.skyblock.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Replaces an island's hopper count with a freshly scanned one. Called by the level scan, which
     * is the only thing that sees every block inside the border at once.
     */
    public void seed(UUID islandId, int count) {
        this.hoppers.computeIfAbsent(islandId, ignored -> new AtomicInteger()).set(count);
    }

    /** Forgets an island's counts. Called when its world unloads; the next scan seeds it again. */
    public void forget(UUID islandId) {
        this.hoppers.remove(islandId);
    }

    /**
     * The hoppers currently counted on an island. An island whose scan has not landed yet counts as
     * empty: the alternative — barring placement until the seed arrives — would refuse a legitimate
     * hopper for the second or two between a world load and its scan, and the scan corrects an
     * over-count on its own.
     */
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
    public void addHopper(UUID islandId) {
        this.hoppers.computeIfAbsent(islandId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** Gives one hopper back to the island's cap. Never drops below zero. */
    public void removeHopper(UUID islandId) {
        AtomicInteger count = this.hoppers.get(islandId);
        if (count == null)
            return;
        count.updateAndGet(current -> Math.max(0, current - 1));
    }

    /** Drops every count. Called on plugin disable. */
    public void clear() {
        this.hoppers.clear();
    }
}
