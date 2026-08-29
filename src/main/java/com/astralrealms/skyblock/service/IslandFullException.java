package com.astralrealms.skyblock.service;

import java.util.UUID;

import lombok.Getter;

/**
 * An island is already at the cap the write would have crossed. Thrown by the member and coop write
 * paths, which enforce the cap themselves rather than trusting their callers to have checked it —
 * two players accepting an invitation at the same moment on two servers both see room in their own
 * cached snapshot, and a shrunk {@code MEMBERS_LIMIT}/{@code COOP_LIMIT} upgrade leaves an island
 * over its cap with no invitation involved at all.
 */
@Getter
public class IslandFullException extends IllegalStateException {

    private final UUID islandId;
    /** The cap that was reached. */
    private final int limit;
    /** Whether the cap was the member cap; otherwise it was the coop cap. */
    private final boolean member;

    public IslandFullException(UUID islandId, int limit, boolean member) {
        super("Island " + islandId + " is at its " + (member ? "member" : "coop") + " limit of " + limit);
        this.islandId = islandId;
        this.limit = limit;
        this.member = member;
    }
}
