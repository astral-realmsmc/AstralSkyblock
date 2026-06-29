package com.astralrealms.skyblock.configuration;

import java.util.List;
import java.util.Map;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;
import com.astralrealms.skyblock.model.role.IslandRole;

@ConfigSerializable
public record RolesConfiguration(Map<String, Entry> roles) {

    /**
     * A configured role. {@code permissions} are raw keys from {@code roles.yml}: either
     * {@link com.astralrealms.skyblock.model.IslandPermission} names or the special {@code ALL}
     * wildcard (grants every permission). They are resolved to an {@code EnumSet} and seeded onto the
     * role at island creation — kept as strings here so the wildcard (and forward-compatible keys)
     * survive deserialization instead of failing the whole config load.
     */
    @ConfigSerializable
    public record Entry(String name, IslandRole.Type type, int weight, @Setting("default") boolean isDefault,
                        ItemStackWrapper icon, List<String> permissions) {
    }

}
