package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import org.bukkit.entity.Player;

public record TransferOwnershipAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<IslandMember> newOwner
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        IslandMember to = context.parseWrapper(this.newOwner);
        AstralSkyblock.get().members().transfer(island, player, to);
    }
}
