package com.astralrealms.skyblock.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;

class RoleGrantTest {

    private final Player editor = mock(Player.class);
    private final Island island = mock(Island.class);
    private final IslandRole member = new IslandRole(1L, UUID.randomUUID(), IslandRole.Type.MEMBER, "Member", 2, true, 0);
    private final IslandRole visitor = new IslandRole(2L, UUID.randomUUID(), IslandRole.Type.VISITOR, "Visitor", 0, false, 0);

    @Test
    void editorsMayOnlyGrantWhatTheyHold() {
        when(island.hasPermission(editor, IslandPermission.BUILD)).thenReturn(true);
        when(island.hasPermission(editor, IslandPermission.KICK_MEMBER)).thenReturn(false);

        assertTrue(RoleService.grantAllowed(editor, island, member, IslandPermission.BUILD));
        assertFalse(RoleService.grantAllowed(editor, island, member, IslandPermission.KICK_MEMBER));
    }

    @Test
    void ownerOnlyGrantsNeedTheOwner() {
        when(island.hasPermission(editor, IslandPermission.SET_PERMISSION)).thenReturn(true);
        when(island.isOwnerOrStaff(editor)).thenReturn(false);
        assertFalse(RoleService.grantAllowed(editor, island, member, IslandPermission.SET_PERMISSION));

        when(island.isOwnerOrStaff(editor)).thenReturn(true);
        assertTrue(RoleService.grantAllowed(editor, island, member, IslandPermission.SET_PERMISSION));
    }

    @Test
    void ownerOnlyGrantsNeverGoToSystemRoles() {
        // Every player on the network resolves through the Visitor role.
        when(island.isOwnerOrStaff(editor)).thenReturn(true);
        assertFalse(RoleService.grantAllowed(editor, island, visitor, IslandPermission.ALL));
        assertFalse(RoleService.grantAllowed(editor, island, visitor, IslandPermission.SET_ROLE));
    }
}
