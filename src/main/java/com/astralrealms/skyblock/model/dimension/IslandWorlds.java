package com.astralrealms.skyblock.model.dimension;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * How island worlds are named. The overworld carries the island's id alone; each dimension adds
 * {@code _<dimension>} to it, so every world of an island shares the id as a prefix.
 */
public final class IslandWorlds {

    private static final int UUID_LENGTH = 36;

    private IslandWorlds() {
    }

    /** The world name of an island's dimension; its overworld's when {@code dimension} is {@code null}. */
    public static String worldName(UUID islandId, @Nullable String dimension) {
        return dimension == null ? islandId.toString() : islandId + "_" + dimension;
    }

    /** Whether {@code worldName} is one of this island's worlds, overworld or dimension. */
    public static boolean belongsTo(String worldName, UUID islandId) {
        String id = islandId.toString();
        return worldName.equals(id) || worldName.startsWith(id + "_");
    }

    /** The island and dimension a world name stands for; empty when it is not an island world name. */
    public static Optional<Name> parse(String worldName) {
        if (worldName == null || worldName.length() < UUID_LENGTH)
            return Optional.empty();

        UUID islandId;
        try {
            islandId = UUID.fromString(worldName.substring(0, UUID_LENGTH));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (worldName.length() == UUID_LENGTH)
            return Optional.of(new Name(islandId, null));
        if (worldName.charAt(UUID_LENGTH) != '_' || worldName.length() == UUID_LENGTH + 1)
            return Optional.empty();
        return Optional.of(new Name(islandId, worldName.substring(UUID_LENGTH + 1)));
    }

    /** A parsed island world name; {@code dimension} is {@code null} for the overworld. */
    public record Name(UUID islandId, @Nullable String dimension) {
    }
}
