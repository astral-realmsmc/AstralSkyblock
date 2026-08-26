package com.astralrealms.skyblock.model.island;

import java.util.Locale;
import java.util.UUID;

/**
 * Cache key of an island warp. Warp names are unique per island case-insensitively (the
 * {@code island_warps} collation is {@code utf8mb4_unicode_ci}), so the key normalises the name to
 * lower case — otherwise {@code Home} and {@code home} would be two cache entries backed by the
 * same database row.
 */
public record WarpKey(UUID islandId, String name) {

    public WarpKey {
        name = name == null ? null : name.toLowerCase(Locale.ROOT);
    }
}
