package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.core.utils.FutureUtils;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.RolesConfiguration;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.repository.RoleRepository;

public class RoleService {

    private final AstralSkyblock plugin;
    private final RoleRepository repository;

    public RoleService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new RoleRepository(plugin);
    }

    public CompletableFuture<Collection<IslandRole>> saveDefaults(UUID islandId) {
        List<CompletableFuture<IslandRole>> roles = this.plugin.rolesConfiguration()
                .roles()
                .values()
                .stream()
                .map(entry -> this.save(islandId, entry))
                .toList();
        // TODO: Create permissions
        return FutureUtils.allOf(roles);
    }

    private CompletableFuture<IslandRole> save(UUID islandId, RolesConfiguration.Entry entry) {
        IslandRole role = new IslandRole(
                null,
                islandId,
                entry.type(),
                entry.name(),
                entry.weight(),
                entry.isDefault(),
                System.currentTimeMillis()
        );
        return this.repository.save(role);
    }

    public CompletableFuture<IslandRole> save(IslandRole role) {
        return this.repository.save(role);
    }
}
