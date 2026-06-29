package com.astralrealms.skyblock.model.role;

import java.util.Set;

import com.astralrealms.skyblock.model.IslandPermission;

/**
 * A role to be created together with its initial permission grants. Built from {@code roles.yml} at
 * island creation and consumed by the single creation transaction
 * ({@link com.astralrealms.skyblock.repository.IslandRepository#create}); the {@link IslandRole}
 * carries a {@code null} id until the insert assigns one.
 */
public record RoleSeed(IslandRole role, Set<IslandPermission> permissions) {
}
