package com.astralrealms.skyblock.action.island.settings;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.role.IslandPermission;

public record ToggleIslandSettingAction(PlaceholderWrapper<Island> island,
                                        PlaceholderWrapper<IslandSettings> setting) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.SET_SETTINGS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        IslandSettings setting = context.parseWrapper(this.setting);
        // Only recorded as this player's unsaved edit; nothing changes in the world until the
        // menu is closed and the save goes through.
        Sound sound = island.toggleSetting(player.getUniqueId(), setting) ? Sound.BLOCK_LEVER_CLICK : Sound.UI_BUTTON_CLICK;
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }
}
