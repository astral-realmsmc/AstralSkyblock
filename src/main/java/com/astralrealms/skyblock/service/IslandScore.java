package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The scoring state of one hosted island: the value of every chunk it last scanned and the chunks
 * whose blocks changed since. Lets a rescan read only the dirty chunks and add the rest from cache,
 * so an island running a cobblestone generator rescans one chunk rather than its whole border.
 *
 * <p>Until a full scan has run since the world loaded there is no baseline: a dirty chunk's old
 * value is unknown, so the next scan has to be a full one. Main thread only.
 */
final class IslandScore {

    private final Map<Long, Long> chunkValues = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private boolean baseline;

    /** Notes that a chunk's blocks changed since it was last scanned. */
    void markDirty(long chunk) {
        this.dirty.add(chunk);
    }

    boolean isDirty() {
        return !this.dirty.isEmpty();
    }

    /** Whether a full scan has established every chunk's value, so partial rescans are possible. */
    boolean hasBaseline() {
        return this.baseline;
    }

    /** The chunks changed since their last scan. A copy. */
    Set<Long> dirtyChunks() {
        return new HashSet<>(this.dirty);
    }

    /** Every chunk this island is known to have scored, dirty or not. A copy. */
    Set<Long> knownChunks() {
        Set<Long> known = new HashSet<>(this.chunkValues.keySet());
        known.addAll(this.dirty);
        return known;
    }

    /** Called as a scan captures a chunk: changes from here on are the next scan's business. */
    void captured(long chunk) {
        this.dirty.remove(chunk);
    }

    /** Drops dirty marks a scan will never read (chunks outside the border). */
    void discardDirty(Collection<Long> chunks) {
        this.dirty.removeAll(chunks);
    }

    /** Hands chunks back to the next scan, when the one that captured them failed. */
    void restoreDirty(Collection<Long> chunks) {
        this.dirty.addAll(chunks);
    }

    /**
     * Records a finished scan. A full scan replaces every chunk value and establishes the baseline;
     * a partial one only overwrites the chunks it read.
     */
    void apply(Map<Long, Long> values, boolean full) {
        if (full) {
            this.chunkValues.clear();
            this.baseline = true;
        }
        values.forEach((chunk, value) -> {
            if (value != 0)
                this.chunkValues.put(chunk, value);
            else
                this.chunkValues.remove(chunk);
        });
    }

    /** The island's value: the sum of its scanned chunks. */
    long total() {
        long total = 0;
        for (long value : this.chunkValues.values())
            total += value;
        return total;
    }
}
