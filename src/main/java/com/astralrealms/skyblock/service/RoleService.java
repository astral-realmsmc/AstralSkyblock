package com.astralrealms.skyblock.service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.configuration.RolesConfiguration;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.model.role.RoleSeed;
import com.astralrealms.skyblock.repository.RoleRepository;
import com.astralrealms.skyblock.utils.PlayerText;

public class RoleService {

    /**
     * Permissions that let their holder hand out (or create roles carrying) any other permission.
     * Only the owner may grant them, and never to the VISITOR or COOP role: every player on the
     * network resolves through those.
     */
    private static final Set<IslandPermission> OWNER_ONLY_GRANTS =
            EnumSet.of(IslandPermission.ALL, IslandPermission.SET_PERMISSION, IslandPermission.SET_ROLE);

    private final AstralSkyblock plugin;
    private final RoleRepository repository;

    public RoleService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new RoleRepository(plugin);
    }

    public RoleRepository repository() {
        return this.repository;
    }

    /**
     * Every role of an island, senior first. Primes (and refreshes) the per-island role slice.
     */
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

    /**
     * Whether {@code editor} may grant {@code permission} to {@code role}. Granting is limited to
     * what the editor holds themselves — otherwise anyone allowed to edit a lower role could give it
     * (or every visitor) powers they were never trusted with.
     */
    public boolean mayGrant(Player editor, Island island, IslandRole role, IslandPermission permission) {
        if (OWNER_ONLY_GRANTS.contains(permission))
            return role.kind() == IslandRole.Type.MEMBER && island.isOwnerOrStaff(editor);
        return island.hasPermission(editor, permission);
    }

    public void updatePermissions(Player player, Island island, IslandRole role) {
        // Taken first: a refused save discards the edits rather than leaving them pending.
        Map<IslandPermission, Boolean> permissions = role.takePendingPermissions(player.getUniqueId());

        if (!island.hasPermission(player, IslandPermission.SET_PERMISSION)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        } else if (!island.canEditRole(player, role)) {
            ASMessages.ROLE_PERMISSION_HIGHER.message(player);
            return;
        }
        if (permissions.isEmpty())
            return;

        // Re-checked at save time: the editor's own permissions may have changed since the toggle.
        boolean refused = permissions.entrySet().stream()
                .anyMatch(entry -> entry.getValue() && !mayGrant(player, island, role, entry.getKey()));
        if (refused) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        this.repository.updateRolePermissions(island.uniqueId(), role.id(), permissions)
                .whenComplete((result, exception) -> {
                    PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                            .registerPlaceholder(island)
                            .registerPlaceholder(role);
                    if (exception != null) {
                        this.plugin.getSLF4JLogger().error("Failed to update permissions for role {} on island {}", role.id(), island.uniqueId(), exception);
                        ASMessages.ROLE_PERMISSION_UPDATE_FAILURE.message(player, placeholders);
                        return;
                    } else if (!result) {
                        ASMessages.ROLE_PERMISSION_UPDATE_FAILURE.message(player, placeholders);
                        return;
                    }

                    // Only now that it is stored. The repository re-cascades the island anyway, but
                    // this server should not wait for that to enforce the change.
                    role.applyPermissions(permissions);
                    ASMessages.ROLE_PERMISSION_UPDATE_SUCCESS.message(player, placeholders);
                });

    }

    /**
     * Creates a new member role on an island. The creator must hold {@link IslandPermission#SET_ROLE}
     * and, unless they own the island, may only create roles below their own weight — otherwise a
     * member could mint a role that outranks the one they hold.
     *
     * <p>The name is bounded and MiniMessage-escaped: it is rendered in menus other members open.
     */
    public CompletableFuture<Void> create(Island island, Player player, String name, int weight) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island)
                .registerDirect("role_name", name);

        if (!island.hasPermission(player, IslandPermission.SET_ROLE)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }
        String sanitised = PlayerText.sanitise(name);
        if (sanitised == null || !PlayerText.withinLimit(name, PlayerText.ROLE_NAME_LIMIT)) {
            ASMessages.ROLE_INVALID_NAME.message(player, placeholders.registerDirect("maximum", PlayerText.ROLE_NAME_LIMIT));
            return CompletableFuture.completedFuture(null);
        }
        if (!canUseWeight(island, player, weight)) {
            ASMessages.ROLE_WEIGHT_TOO_HIGH.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        // Every role is loaded with the island and listed in its menus; without a cap a member with
        // SET_ROLE could create thousands.
        int maximum = this.plugin.configuration().maximumRoles();
        long custom = island.roles().stream().filter(role -> role.kind() == IslandRole.Type.MEMBER).count();
        if (custom >= maximum) {
            ASMessages.ROLE_LIMIT_REACHED.message(player, placeholders.registerDirect("maximum", maximum));
            return CompletableFuture.completedFuture(null);
        }

        return this.repository.create(island.uniqueId(), sanitised, weight)
                .thenCompose(role -> this.plugin.islands()
                        .refreshRelationships(island.uniqueId())
                        .thenApply(ignored -> role))
                .handle((role, exception) -> {
                    if (exception != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to create a role on island {}", island.uniqueId(), exception);
                        return null;
                    }

                    ASMessages.ROLE_CREATED.message(player, placeholders.registerPlaceholder(role));
                    return null;
                });
    }

    /**
     * Renames a role and changes its weight. The editor must hold {@link IslandPermission#SET_ROLE}
     * and outrank the role, and may not lift it to or above their own weight.
     */
    public CompletableFuture<Void> update(Island island, Player player, IslandRole role, String name, int weight) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island)
                .registerPlaceholder(role)
                .registerDirect("role_name", name);

        if (!island.hasPermission(player, IslandPermission.SET_ROLE)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }
        if (!island.canEditRole(player, role)) {
            ASMessages.ROLE_PERMISSION_HIGHER.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (role.kind() != IslandRole.Type.MEMBER) {
            // VISITOR and COOP are structural: the island resolves permissions through them by kind.
            ASMessages.ROLE_NOT_EDITABLE.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        String sanitised = PlayerText.sanitise(name);
        if (sanitised == null || !PlayerText.withinLimit(name, PlayerText.ROLE_NAME_LIMIT)) {
            ASMessages.ROLE_INVALID_NAME.message(player, placeholders.registerDirect("maximum", PlayerText.ROLE_NAME_LIMIT));
            return CompletableFuture.completedFuture(null);
        }
        if (!canUseWeight(island, player, weight)) {
            ASMessages.ROLE_WEIGHT_TOO_HIGH.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return this.repository.renameAndReweight(role.id(), sanitised, weight)
                .thenCompose(ignored -> this.plugin.islands().refreshRelationships(island.uniqueId()))
                .handle((ignored, exception) -> {
                    if (exception != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to update role {} on island {}", role.id(), island.uniqueId(), exception);
                        return null;
                    }

                    ASMessages.ROLE_UPDATED.message(player, placeholders);
                    return null;
                });
    }

    /**
     * Whether {@code player} may hand out a role of this weight: positive (0 is the visitor floor),
     * and below their own unless they own the island.
     */
    private boolean canUseWeight(Island island, Player player, int weight) {
        if (weight <= 0)
            return false;
        if (player.hasPermission("skyblock.admin"))
            return true;

        return island.findMember(player.getUniqueId())
                .map(member -> member.isOwner() || member.role() == null || weight < member.role().weight())
                .orElse(false);
    }

    public CompletableFuture<IslandRole> save(IslandRole role) {
        return this.repository.save(role);
    }
}
