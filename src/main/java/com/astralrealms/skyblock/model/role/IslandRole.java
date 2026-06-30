package com.astralrealms.skyblock.model.role;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

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
    private transient EnumSet<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
    private final transient Map<IslandPermission, Boolean> dirtyPermissions = new EnumMap<>(IslandPermission.class);

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
    public boolean togglePermission(IslandPermission permission) {
        boolean hasPermission = this.hasPermission(permission);
        boolean newValue = !hasPermission;
        this.dirtyPermissions.put(permission, newValue);
        return newValue;
    }

    public boolean hasPermission(IslandPermission permission) {
        return dirtyPermissions.getOrDefault(permission, permissions.contains(permission))
               || dirtyPermissions.getOrDefault(IslandPermission.ALL, permissions.contains(IslandPermission.ALL));
    }

    public Map<IslandPermission, Boolean> flushPermissions() {
        Map<IslandPermission, Boolean> flushed = Map.copyOf(dirtyPermissions);
        for (Map.Entry<IslandPermission, Boolean> entry : flushed.entrySet()) {
            if (entry.getValue())
                permissions.add(entry.getKey());
            else
                permissions.remove(entry.getKey());
        }
        this.dirtyPermissions.clear();
        return flushed;
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
