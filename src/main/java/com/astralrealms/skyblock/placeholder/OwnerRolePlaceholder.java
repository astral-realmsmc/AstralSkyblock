package com.astralrealms.skyblock.placeholder;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.AstralSkyblock;

/**
 * Stands in for the role of an island's owner, who holds none: {@code name} resolves to the
 * configured {@code owner-role-name}, so member menus show a rank for everyone.
 */
public class OwnerRolePlaceholder implements ComplexPlaceholder {

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;
        return switch (context.next()) {
            case "name" -> AstralSkyblock.get().configuration().ownerRoleName();
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "role";
    }
}
