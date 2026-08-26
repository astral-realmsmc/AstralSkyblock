package com.astralrealms.skyblock.action.island.role;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

/**
 * Dialog action: {@code [create-role] <island> <name> <weight>}. The roles menu opens the
 * {@code island-role-create} dialog, whose confirm button lands here with the typed name and the
 * chosen weight.
 */
public record CreateRoleAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<String> name,
        PlaceholderWrapper<Integer> weight
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        String name = context.parseWrapper(this.name);
        Integer weight = context.parseWrapper(this.weight);
        AstralSkyblock.get().roles().create(island, player, name, weight == null ? 0 : weight);
    }
}
