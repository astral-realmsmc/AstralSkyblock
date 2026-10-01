package com.astralrealms.skyblock.action.island.role;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandRole;

/**
 * Menu action: {@code [set-default-role] <island> <role>}. Makes the role the one new members receive.
 */
public record SetDefaultRoleAction(PlaceholderWrapper<Island> island, PlaceholderWrapper<IslandRole> role) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        AstralSkyblock.get().roles().setDefault(context.parseWrapper(this.island), player, context.parseWrapper(this.role));
    }
}
