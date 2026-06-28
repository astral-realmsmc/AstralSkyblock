package com.astralrealms.skyblock.configuration;

import java.util.Map;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;
import com.astralrealms.skyblock.model.role.IslandRole;

@ConfigSerializable
public record RolesConfiguration(Map<String, Entry> roles) {

    @ConfigSerializable
    public record Entry(String name, IslandRole.Type type, int weight, @Setting("default") boolean isDefault,
                        ItemStackWrapper icon) {
    }

}
