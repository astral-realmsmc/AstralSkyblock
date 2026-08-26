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
 * Dialog action: {@code [edit-role] <island> <role> <name> <weight>}. The roles menu opens the
 * {@code island-role-edit} dialog prefilled with the role's current name and weight.
 */
public record EditRoleAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<IslandRole> role,
        PlaceholderWrapper<String> name,
        PlaceholderWrapper<Integer> weight
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        IslandRole role = context.parseWrapper(this.role);
        String name = context.parseWrapper(this.name);
        Integer weight = context.parseWrapper(this.weight);
        AstralSkyblock.get().roles().update(island, player, role, name, weight == null ? 0 : weight);
    }
}
