package com.astralrealms.skyblock.action.island.settings;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

public record UpdateIslandSettingsAction(PlaceholderWrapper<Island> island) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);

        AstralSkyblock.get()
                .islands()
                .updateSettings(player, island);
    }

}
