package com.astralrealms.skyblock.placeholder.permissions;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.placeholder.itemstack.ItemStackPlaceholder;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandPermissionPlaceholder implements ComplexPlaceholder {

    private final IslandRole role;
    private final IslandPermission permission;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return permission;

        return switch (context.next()) {
            case "id" -> permission.name();
            case "item" -> new ItemStackPlaceholder(this.permission.value().get(context.function()));
            case "enabled" -> context.context() instanceof Player viewer
                               ? role.isGrantedFor(viewer.getUniqueId(), permission)
                               : role.hasPermission(permission);
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "permission";
    }

}
