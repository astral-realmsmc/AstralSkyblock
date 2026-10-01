package com.astralrealms.skyblock.configuration;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.skyblock.AstralSkyblock;

/**
 * Block values from {@code block-values.yml}. A key is one of:
 * <ul>
 *     <li>a material name ({@code DIAMOND_BLOCK});</li>
 *     <li>a block tag, prefixed with {@code #} ({@code '#minecraft:logs'}), valuing every block in
 *     it;</li>
 *     <li>a material name pattern with {@code *} wildcards ({@code '*_CONCRETE'}), for families
 *     vanilla has no tag for;</li>
 *     <li>a spawner type, {@code MOB_SPAWNER:<ENTITY>}.</li>
 * </ul>
 * A material listed on its own overrides any tag or pattern it also belongs to. Keys that resolve
 * to nothing are reported once, at first use, rather than silently ignored.
 */
@ConfigSerializable
public class BlockValueConfiguration {

    private static final String SPAWNER_PREFIX = "MOB_SPAWNER:";
    private static final String TAG_PREFIX = "#";
    private static final String WILDCARD = "*";

    @Setting("blocks")
    private Map<String, Integer> raw;
    // Resolved lazily, then published whole: the level scan reads these off the main thread.
    private transient volatile Map<Material, Integer> materialValues;
    private transient volatile Map<EntityType, Integer> spawnerValues;

    /**
     * Plain material values, with tags expanded. Unlike spawners these need no live block, so the
     * island scanner can sum them off a {@code ChunkSnapshot} off-thread.
     */
    public Map<Material, Integer> materialValues() {
        Map<Material, Integer> resolved = this.materialValues;
        if (resolved != null)
            return resolved;

        Map<Material, Integer> values = new EnumMap<>(Material.class);
        // Tags and patterns first, so that a material listed on its own wins over them.
        for (Map.Entry<String, Integer> entry : entries()) {
            String key = entry.getKey();
            if (key.startsWith(TAG_PREFIX)) {
                Tag<Material> tag = blockTag(key.substring(TAG_PREFIX.length()));
                if (tag == null)
                    warnUnknown(key);
                else
                    tag.getValues().forEach(material -> values.put(material, entry.getValue()));
            } else if (key.contains(WILDCARD)) {
                Pattern pattern = Pattern.compile(Arrays.stream(key.toUpperCase(Locale.ROOT).split("\\*", -1))
                        .map(Pattern::quote)
                        .collect(Collectors.joining(".*")));
                boolean matched = false;
                for (Material material : Material.values()) {
                    if (material.isBlock() && !material.isLegacy() && pattern.matcher(material.name()).matches()) {
                        values.put(material, entry.getValue());
                        matched = true;
                    }
                }
                if (!matched)
                    warnUnknown(key);
            }
        }
        for (Map.Entry<String, Integer> entry : entries()) {
            String key = entry.getKey();
            if (key.startsWith(TAG_PREFIX) || key.contains(WILDCARD) || key.startsWith(SPAWNER_PREFIX))
                continue;
            Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock())
                warnUnknown(key);
            else
                values.put(material, entry.getValue());
        }

        this.materialValues = values;
        return values;
    }

    /**
     * Per-entity spawner values. Spawners carry their entity type in their block state, which a
     * snapshot cannot see, so the scanner resolves those few positions on the main thread.
     */
    public Map<EntityType, Integer> spawnerValues() {
        Map<EntityType, Integer> resolved = this.spawnerValues;
        if (resolved != null)
            return resolved;

        Map<EntityType, Integer> values = new EnumMap<>(EntityType.class);
        for (Map.Entry<String, Integer> entry : entries()) {
            if (!entry.getKey().startsWith(SPAWNER_PREFIX))
                continue;
            String name = entry.getKey().substring(SPAWNER_PREFIX.length()).toUpperCase(Locale.ROOT);
            try {
                values.put(EntityType.valueOf(name), entry.getValue());
            } catch (IllegalArgumentException ignored) {
                warnUnknown(entry.getKey());
            }
        }

        this.spawnerValues = values;
        return values;
    }

    /** Whether {@code material} is worth at least {@code threshold} points. */
    public boolean isValuable(Material material, int threshold) {
        Integer value = materialValues().get(material);
        return value != null && value >= threshold;
    }

    private Iterable<Map.Entry<String, Integer>> entries() {
        return this.raw == null ? Map.<String, Integer>of().entrySet() : this.raw.entrySet();
    }

    private static Tag<Material> blockTag(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        return namespacedKey == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, namespacedKey, Material.class);
    }

    private static void warnUnknown(String key) {
        AstralSkyblock.get().getSLF4JLogger().warn("block-values.yml: '{}' is not a known block, block tag or spawner type; ignoring it.", key);
    }
}
