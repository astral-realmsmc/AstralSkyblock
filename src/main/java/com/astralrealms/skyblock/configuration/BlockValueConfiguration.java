package com.astralrealms.skyblock.configuration;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public class BlockValueConfiguration {

    private static final String SPAWNER_PREFIX = "MOB_SPAWNER:";

    @Setting("blocks")
    private Map<String, Integer> raw;
    private transient Map<Predicate<Block>, Integer> blocks;
    private transient Map<Material, Integer> materialValues;
    private transient Map<EntityType, Integer> spawnerValues;

    public Map<Predicate<Block>, Integer> blocks() {
        if (this.blocks == null) {
            this.blocks = new HashMap<>();
            for (Map.Entry<String, Integer> entry : raw.entrySet()) {
                String material = entry.getKey();
                Integer value = entry.getValue();
                if (material.startsWith(SPAWNER_PREFIX)) {
                    String entityType = material.substring(SPAWNER_PREFIX.length());
                    this.blocks.put(block -> block.getType().equals(Material.SPAWNER)
                                             && block.getState() instanceof CreatureSpawner spawner
                                             && spawner.getSpawnedType() != null
                                             && spawner.getSpawnedType().name().equalsIgnoreCase(entityType), value);
                    continue;
                }
                this.blocks.put(block -> block.getType().name().equalsIgnoreCase(material), value);
            }
        }
        return this.blocks;
    }

    /**
     * Plain material values, resolved once. Unlike {@link #blocks()} these need no live
     * {@link Block}, so the island scanner can sum them off a {@code ChunkSnapshot} off-thread.
     * Unknown material names are skipped (the shipped file carries some legacy keys).
     */
    public Map<Material, Integer> materialValues() {
        if (this.materialValues == null) {
            Map<Material, Integer> values = new EnumMap<>(Material.class);
            for (Map.Entry<String, Integer> entry : raw.entrySet()) {
                if (entry.getKey().startsWith(SPAWNER_PREFIX))
                    continue;
                Material material = Material.matchMaterial(entry.getKey().toUpperCase(Locale.ROOT));
                if (material != null)
                    values.put(material, entry.getValue());
            }
            this.materialValues = values;
        }
        return this.materialValues;
    }

    /**
     * Per-entity spawner values. Spawners carry their entity type in their block state, which a
     * snapshot cannot see, so the scanner resolves those few positions on the main thread.
     */
    public Map<EntityType, Integer> spawnerValues() {
        if (this.spawnerValues == null) {
            Map<EntityType, Integer> values = new EnumMap<>(EntityType.class);
            for (Map.Entry<String, Integer> entry : raw.entrySet()) {
                if (!entry.getKey().startsWith(SPAWNER_PREFIX))
                    continue;
                String name = entry.getKey().substring(SPAWNER_PREFIX.length()).toUpperCase(Locale.ROOT);
                try {
                    values.put(EntityType.valueOf(name), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Unknown entity type in block-values.yml — skipped, like unknown materials.
                }
            }
            this.spawnerValues = values;
        }
        return this.spawnerValues;
    }
}
