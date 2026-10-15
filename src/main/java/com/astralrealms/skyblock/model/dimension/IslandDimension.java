package com.astralrealms.skyblock.model.dimension;

import java.util.Map;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;

/**
 * An extra world every island may have next to its overworld (a nether, an end, ...), declared in
 * {@code dimensions.yml}. Its world is cloned from {@link #sourceWorld} the first time someone enters
 * it, and from then on lives and dies with the island's overworld.
 *
 * @param id          the key it is declared under; filled in by the service, never read from the file
 * @param generators  generator overrides by {@code GENERATOR} upgrade level: each entry applies from
 *                    that level up to the next entry. Levels below the first entry use the island's
 *                    own generator, as does a dimension without overrides.
 */
@ConfigSerializable
public record IslandDimension(String id, String displayName, World.Environment environment, String sourceWorld,
                              String defaultBiome, MinecraftLocation spawnLocation, DimensionPortal portal,
                              String permission, Map<Integer, String> generators) implements ComplexPlaceholder {

    /** Reserved: it names the island's own world wherever a dimension id is expected. */
    public static final String OVERWORLD = "overworld";

    public IslandDimension withId(String id) {
        return new IslandDimension(id, displayName, environment, sourceWorld, defaultBiome, spawnLocation, portal,
                permission, generators);
    }

    @Override
    public String displayName() {
        return this.displayName == null || this.displayName.isBlank() ? this.id : this.displayName;
    }

    @Override
    public DimensionPortal portal() {
        return this.portal == null ? DimensionPortal.NONE : this.portal;
    }

    /** The node a player needs to enter this dimension, on top of the island having unlocked it. */
    @Override
    public String permission() {
        return this.permission == null || this.permission.isBlank() ? "skyblock.dimension." + this.id : this.permission;
    }

    @Override
    public String defaultBiome() {
        return this.defaultBiome == null || this.defaultBiome.isBlank() ? defaultBiomeOf(environment()) : this.defaultBiome;
    }

    @Override
    public World.Environment environment() {
        return this.environment == null ? World.Environment.NORMAL : this.environment;
    }

    /**
     * The generator overriding the island's own at this {@code GENERATOR} level: the entry with the
     * highest level at or below it, or {@code null} when none applies.
     */
    @Nullable
    public String generatorAt(int level) {
        return generatorAt(this.generators, level);
    }

    @Nullable
    static String generatorAt(@Nullable Map<Integer, String> generators, int level) {
        if (generators == null)
            return null;
        return generators.entrySet().stream()
                .filter(entry -> entry.getKey() <= level && entry.getValue() != null && !entry.getValue().isBlank())
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /** What ASP's {@code environment} property calls this dimension's environment. */
    public String slimeEnvironment() {
        return switch (environment()) {
            case NETHER -> "nether";
            case THE_END -> "the_end";
            default -> "normal";
        };
    }

    private static String defaultBiomeOf(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "minecraft:nether_wastes";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:plains";
        };
    }

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> id;
            case "name" -> displayName();
            case "environment" -> environment().name();
            case "permission" -> permission();
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "dimension";
    }
}
