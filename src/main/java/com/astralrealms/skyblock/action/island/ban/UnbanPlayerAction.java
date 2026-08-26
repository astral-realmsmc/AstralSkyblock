package com.astralrealms.skyblock.action.island.ban;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

/** Menu action: {@code [unban-player] <island> <target>}. */
public record UnbanPlayerAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        UUID target = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().bans().unban(island, player, target);
    }
}
