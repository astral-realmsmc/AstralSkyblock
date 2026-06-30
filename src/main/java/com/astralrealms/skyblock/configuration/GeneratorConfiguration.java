package com.astralrealms.skyblock.configuration;

import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.core.utils.RandomCollection;

import lombok.Getter;

@Getter
@ConfigSerializable
public class GeneratorConfiguration {

    private String id;
    private Map<Material, Double> blocks;
    private transient RandomCollection<BlockData> randomCollection;

    public BlockData randomBlock() {
        return randomCollection().next();
    }

    public RandomCollection<BlockData> randomCollection() {
        if (randomCollection == null) {
            randomCollection = new RandomCollection<>();
            for (Map.Entry<Material, Double> entry : blocks.entrySet()) {
                if (!entry.getKey().isBlock())
                    continue;

                randomCollection.add(entry.getValue(), entry.getKey().createBlockData());
            }
        }
        return randomCollection;
    }
}
