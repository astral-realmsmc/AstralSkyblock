package com.astralrealms.skyblock.action.island.role;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandRole;

/** Menu action: {@code [set-member-role] <island> <member> <role>}. */
public record SetMemberRoleAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid,
        PlaceholderWrapper<IslandRole> role
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        UUID target = context.parseWrapper(this.targetUuid);
        IslandRole role = context.parseWrapper(this.role);
        AstralSkyblock.get().members().setRole(island, player, target, role);
    }
}
