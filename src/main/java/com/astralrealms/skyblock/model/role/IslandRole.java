package com.astralrealms.skyblock.model.role;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Column;
import com.astralrealms.core.storage.annotation.CreatedAt;
import com.astralrealms.core.storage.annotation.Entity;
import com.astralrealms.core.storage.annotation.Id;
import com.astralrealms.core.storage.model.SQLAccessor;
import com.astralrealms.skyblock.placeholder.permissions.IslandPermissionsItemProvider;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity("island_roles")
@NoArgsConstructor
public class IslandRole implements ComplexPlaceholder {

    @Id
    @Column("id")
    private Long id;
    private UUID islandId;
    private Type kind;
    private String name;
    private int weight;
    private boolean isDefault;
    @CreatedAt
    @Column(type = SQLAccessor.LONG_TIMESTAMP)
    private long createdAt;

    // Relationships — permission loading is deferred (see PermissionRepository follow-up).
    @Setter
    private transient volatile EnumSet<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
    // Unsaved permissions-menu edits, per editor. Enforcement never reads them: an edit only takes
    // effect once it is saved, and two editors never see (or save) each other's.
    private final transient Map<UUID, Map<IslandPermission, Boolean>> pendingPermissions = new ConcurrentHashMap<>();

    public IslandRole(Long id, UUID islandId, Type kind, String name, int weight, boolean isDefault, long createdAt) {
        this.id = id;
        this.islandId = islandId;
        this.kind = kind;
        this.name = name;
        this.weight = weight;
        this.isDefault = isDefault;
        this.createdAt = createdAt;
    }

    // Permissions

    /** Whether the role holds {@code permission}, as saved. */
    public boolean hasPermission(IslandPermission permission) {
        EnumSet<IslandPermission> granted = this.permissions;
        return granted.contains(permission) || granted.contains(IslandPermission.ALL);
    }

    /** Whether {@code permission} is granted, as {@code editor} sees it in the menu: their unsaved edit, else the saved value. */
    public boolean isGrantedFor(UUID editor, IslandPermission permission) {
        Map<IslandPermission, Boolean> pending = this.pendingPermissions.get(editor);
        Boolean value = pending == null ? null : pending.get(permission);
        return value != null ? value : this.permissions.contains(permission);
    }

    /** Flips {@code permission} in {@code editor}'s unsaved edits and returns the new value. */
    public boolean togglePermission(UUID editor, IslandPermission permission) {
        boolean granted = !isGrantedFor(editor, permission);
        this.pendingPermissions
                .computeIfAbsent(editor, _ -> new ConcurrentHashMap<>())
                .put(permission, granted);
        return granted;
    }

    /** Removes {@code editor}'s unsaved edits and returns those that actually change the role. */
    public Map<IslandPermission, Boolean> takePendingPermissions(UUID editor) {
        Map<IslandPermission, Boolean> pending = this.pendingPermissions.remove(editor);
        if (pending == null)
            return Map.of();
        Map<IslandPermission, Boolean> changes = new EnumMap<>(IslandPermission.class);
        pending.forEach((permission, granted) -> {
            if (granted != this.permissions.contains(permission))
                changes.put(permission, granted);
        });
        return changes;
    }

    /** Applies changes that were just persisted. */
    public void applyPermissions(Map<IslandPermission, Boolean> changes) {
        EnumSet<IslandPermission> updated = EnumSet.copyOf(this.permissions);
        changes.forEach((permission, granted) -> {
            if (granted)
                updated.add(permission);
            else
                updated.remove(permission);
        });
        this.permissions = updated;
    }

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "id" -> id;
            case "islandId" -> islandId;
            case "kind" -> kind;
            case "name" -> name;
            case "weight" -> weight;
            case "permissions" -> new IslandPermissionsItemProvider(this);
            case "default" -> isDefault;
            case "createdAt" -> createdAt;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "role";
    }

    public enum Type {
        MEMBER,
        VISITOR,
        COOP
    }
}
