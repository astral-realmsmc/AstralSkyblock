package com.astralrealms.skyblock.configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public class BlockValueConfiguration {

    @Setting("blocks")
    private Map<String, Integer> raw;
    private transient Map<Predicate<Block>, Integer> blocks;

    public Map<Predicate<Block>, Integer> blocks() {
        if (this.blocks == null) {
            this.blocks = new HashMap<>();
            for (Map.Entry<String, Integer> entry : raw.entrySet()) {
                String material = entry.getKey();
                Integer value = entry.getValue();
                if (material.startsWith("MOB_SPAWNER:")) {
                    String entityType = material.substring("MOB_SPAWNER:".length());
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
}
