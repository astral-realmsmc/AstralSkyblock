package com.astralrealms.skyblock.action.island.role;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.model.role.IslandRole;

public record UpdateRolePermissionsAction(PlaceholderWrapper<IslandRole> role) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        IslandRole role = context.parseWrapper(this.role);
        // TODO: Implement the logic to update role permissions
    }

}
