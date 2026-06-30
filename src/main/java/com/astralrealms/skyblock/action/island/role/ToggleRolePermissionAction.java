package com.astralrealms.skyblock.action.island.role;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;

public record ToggleRolePermissionAction(PlaceholderWrapper<Island> island, PlaceholderWrapper<IslandRole> role,
                                         PlaceholderWrapper<IslandPermission> permission) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.SET_PERMISSION)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        IslandRole role = context.parseWrapper(this.role);
        if (!island.canEditRole(player, role)) {
            ASMessages.ROLE_PERMISSION_HIGHER.message(player);
            return;
        }

        IslandPermission permission = context.parseWrapper(this.permission);
        Sound sound = role.togglePermission(permission) ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.UI_BUTTON_CLICK;
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }

}
