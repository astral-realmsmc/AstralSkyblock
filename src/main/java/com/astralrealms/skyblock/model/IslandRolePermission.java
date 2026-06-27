package com.astralrealms.skyblock.model;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.storage.annotation.Entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity("island_role_permissions")
@NoArgsConstructor
@AllArgsConstructor
public class IslandRolePermission implements ComplexPlaceholder {

    private Long roleId;
    private String permission;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "roleId" -> roleId;
            case "permission" -> permission;
            default -> null;
        };
    }

    @Override
    public String namespace() {
        return "permission";
    }
}
