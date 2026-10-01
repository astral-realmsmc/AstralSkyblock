package com.astralrealms.skyblock.configuration;

import java.util.Map;

import org.bukkit.Material;
import com.astralrealms.skyblock.AstralSkyblock;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.BlockData;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.core.utils.RandomCollection;

import lombok.Getter;

@Getter
@ConfigSerializable
public class GeneratorConfiguration {

    private String id;
    private Map<Material, Double> blocks;
    // Built once, fully, then published: a half-built collection (one bad weight used to throw
    // mid-way) was cached forever and silently lost every later entry.
    private transient volatile RandomCollection<BlockData> randomCollection;
    private transient volatile boolean empty;

    /** A rolled block, or {@code null} when the generator has nothing usable (vanilla output stays). */
    public BlockData randomBlock() {
        RandomCollection<BlockData> collection = randomCollection();
        return this.empty ? null : collection.next();
    }

    public RandomCollection<BlockData> randomCollection() {
        RandomCollection<BlockData> collection = this.randomCollection;
        if (collection != null)
            return collection;

        collection = new RandomCollection<>();
        int added = 0;
        if (blocks != null) {
            for (Map.Entry<Material, Double> entry : blocks.entrySet()) {
                Material material = entry.getKey();
                Double weight = entry.getValue();
                if (material == null || !material.isBlock() || weight == null || weight <= 0) {
                    AstralSkyblock.get().getSLF4JLogger().warn("Generator '{}': skipping {} (not a block, or a weight that is not positive)", id, entry);
                    continue;
                }
                BlockData data = material.createBlockData();
                // Default leaves decay on the next random tick when no log is near.
                if (data instanceof Leaves leaves)
                    leaves.setPersistent(true);
                collection.add(weight, data);
                added++;
            }
        }
        if (added == 0)
            AstralSkyblock.get().getSLF4JLogger().warn("Generator '{}' has no usable blocks; it leaves vanilla output alone", id);
        this.empty = added == 0;
        this.randomCollection = collection;
        return collection;
    }
}
