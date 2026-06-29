package com.astralrealms.skyblock.placeholder.permissions;

import com.astralrealms.core.placeholder.Placeholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.skyblock.model.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandPermissionsItemProvider implements ItemProvider {

    private static final int LENGTH = IslandPermission.values().length;
    private final IslandRole role;

    @Override
    public Placeholder item(int i) {
        IslandPermission permission = IslandPermission.values()[i];
        return new IslandPermissionPlaceholder(role, permission);
    }

    @Override
    public int size() {
        return LENGTH;
    }
}
