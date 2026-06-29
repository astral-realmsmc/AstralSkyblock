package com.astralrealms.skyblock.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.RolesConfiguration;
import com.astralrealms.skyblock.model.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.model.role.RoleSeed;
import com.astralrealms.skyblock.repository.RoleRepository;

public class RoleService {

    private final AstralSkyblock plugin;
    private final RoleRepository repository;

    public RoleService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new RoleRepository(plugin);
    }

    /** Every role of an island, senior first. Primes (and refreshes) the per-island role slice. */
    public CompletableFuture<List<IslandRole>> findByIsland(UUID islandId) {
        return this.repository.findByIsland(islandId);
    }

    /**
     * Builds the configured default roles for a new island, each paired with its resolved permission
     * grants. The roles carry a {@code null} id; they are inserted (and their ids assigned) by the
     * single island-creation transaction in {@link com.astralrealms.skyblock.repository.IslandRepository#create}.
     */
    public List<RoleSeed> defaultRoleSeeds(UUID islandId) {
        return this.plugin.rolesConfiguration()
                .roles()
                .values()
                .stream()
                .map(entry -> new RoleSeed(
                        new IslandRole(
                                null,
                                islandId,
                                entry.type(),
                                entry.name(),
                                entry.weight(),
                                entry.isDefault(),
                                System.currentTimeMillis()
                        ),
                        resolvePermissions(entry)))
                .toList();
    }

    /**
     * Turns a role's configured permission keys into an {@link EnumSet}. The {@code ALL} wildcard
     * expands to every {@link IslandPermission}; unknown keys are logged and skipped rather than
     * aborting island creation.
     */
    private EnumSet<IslandPermission> resolvePermissions(RolesConfiguration.Entry entry) {
        EnumSet<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
        if (entry.permissions() == null)
            return permissions;
        for (String key : entry.permissions()) {
            if (key == null || key.isBlank())
                continue;
            String normalized = key.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("ALL"))
                return EnumSet.allOf(IslandPermission.class);
            try {
                permissions.add(IslandPermission.valueOf(normalized));
            } catch (IllegalArgumentException exception) {
                this.plugin.getSLF4JLogger().warn("Unknown permission '{}' for role '{}' in roles.yml; ignoring.", key, entry.name());
            }
        }
        return permissions;
    }

    public CompletableFuture<IslandRole> save(IslandRole role) {
        return this.repository.save(role);
    }
}
