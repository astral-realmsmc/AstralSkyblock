package com.astralrealms.skyblock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;

class IslandPendingEditsTest {

    private final UUID editor = UUID.randomUUID();
    private final UUID otherEditor = UUID.randomUUID();

    private static Island island() {
        return new Island(UUID.randomUUID(), "Atlantis", false, 0, 0, 0.5, 100, 0.5, 0, 0, 0, 0);
    }

    @Test
    void settingTogglesStayPendingUntilApplied() {
        Island island = island();
        island.settings(EnumSet.of(IslandSettings.PVP));

        island.toggleSetting(editor, IslandSettings.PVP);

        // Enforcement still sees the saved value; only the editor's menu shows the edit.
        assertTrue(island.isSettingEnabled(IslandSettings.PVP));
        assertFalse(island.isSettingEnabled(editor, IslandSettings.PVP));
        assertTrue(island.isSettingEnabled(otherEditor, IslandSettings.PVP));

        Map<IslandSettings, Boolean> changes = island.takePendingSettings(editor);
        assertEquals(Map.of(IslandSettings.PVP, false), changes);
        island.applySettings(changes);
        assertFalse(island.isSettingEnabled(IslandSettings.PVP));
    }

    @Test
    void enablingATimeLockSwitchesOffTheOtherOnes() {
        Island island = island();
        island.settings(EnumSet.of(IslandSettings.ALWAYS_DAY));

        island.toggleSetting(editor, IslandSettings.ALWAYS_NIGHT);

        Map<IslandSettings, Boolean> changes = island.takePendingSettings(editor);
        assertEquals(Map.of(IslandSettings.ALWAYS_NIGHT, true, IslandSettings.ALWAYS_DAY, false), changes);
    }

    @Test
    void togglingBackAndForthChangesNothing() {
        Island island = island();
        island.toggleSetting(editor, IslandSettings.PVP);
        island.toggleSetting(editor, IslandSettings.PVP);
        assertTrue(island.takePendingSettings(editor).isEmpty());
    }

    @Test
    void permissionTogglesStayPendingUntilApplied() {
        IslandRole role = new IslandRole(1L, UUID.randomUUID(), IslandRole.Type.MEMBER, "Member", 2, true, 0);
        role.permissions(EnumSet.of(IslandPermission.BUILD));

        role.togglePermission(editor, IslandPermission.BREAK);
        assertFalse(role.hasPermission(IslandPermission.BREAK));
        assertTrue(role.isGrantedFor(editor, IslandPermission.BREAK));

        role.applyPermissions(role.takePendingPermissions(editor));
        assertTrue(role.hasPermission(IslandPermission.BREAK));
    }

    @Test
    void membersAreFoundThroughTheIndex() {
        Island island = island();
        UUID player = UUID.randomUUID();
        island.members(List.of(new IslandMember(island.uniqueId(), player, false, 1L, 0)));

        assertTrue(island.findMember(player).isPresent());
        assertFalse(island.findMember(UUID.randomUUID()).isPresent());

        island.members(List.of());
        assertFalse(island.findMember(player).isPresent());
    }
}
