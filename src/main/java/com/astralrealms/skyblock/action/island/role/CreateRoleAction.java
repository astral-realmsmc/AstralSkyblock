package com.astralrealms.skyblock.action.island.role;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

/**
 * Dialog action: {@code [create-role] <island> <weight> <name>}. The roles menu opens the
 * {@code island-role-create} dialog, whose confirm button lands here with the chosen weight and the
 * typed name. The name comes last: only the last argument takes the rest of the line, so a name
 * with spaces stays whole.
 */
public record CreateRoleAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<Integer> weight,
        PlaceholderWrapper<String> name
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
