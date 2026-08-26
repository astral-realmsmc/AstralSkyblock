package com.astralrealms.skyblock.action.island.warp;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

/** Menu action: {@code [delete-warp] <island> <warp name>}. */
public record DeleteWarpAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<String> warp
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        String warp = context.parseWrapper(this.warp);
        AstralSkyblock.get().warps().delete(island, player, warp);
    }
}
