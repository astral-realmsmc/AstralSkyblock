package com.astralrealms.skyblock.action.island.member;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.role.IslandPermission;
import org.bukkit.entity.Player;

import java.util.UUID;

public record InviteMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        if (!island.hasPermission(player, IslandPermission.INVITE_MEMBER)) return;
        UUID target = context.parseWrapper(this.targetUuid);
        AstralSkyblock.get().invitations()
                .create(island, player.getUniqueId(), target, InvitationType.MEMBER);
    }
}
