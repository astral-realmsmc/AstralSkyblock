package com.astralrealms.skyblock.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;
import com.astralrealms.core.paper.placeholder.itemstack.ItemStackPlaceholder;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;

@ConfigSerializable
public record IslandBlueprint(String id, @Setting("default") boolean isDefault, String sourceWorld, MinecraftLocation spawnLocation,
                              ItemStackWrapper icon) implements ComplexPlaceholder {

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> id;
            case "default" -> isDefault;
            case "icon" -> new ItemStackPlaceholder(icon.get(context.function()));
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "blueprint";
    }
}
