package com.astralrealms.skyblock.action.island.role;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandRole;

public record UpdateRolePermissionsAction(PlaceholderWrapper<Island> island, PlaceholderWrapper<IslandRole> role) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Island island = context.parseWrapper(this.island);
        IslandRole role = context.parseWrapper(this.role);

        AstralSkyblock.get()
                .roles()
                .updatePermissions(context.executor(), island, role);
    }

}
