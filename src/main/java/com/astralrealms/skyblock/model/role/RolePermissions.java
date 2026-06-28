package com.astralrealms.skyblock.model.role;

import java.util.HashSet;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The grant set of a single role — the cacheable unit for permissions. Point checks on the
 * hot path resolve against the in-memory set rather than hitting the database per event.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissions {

    private long roleId;
    private Set<String> permissions = new HashSet<>();

    public boolean has(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
