package com.astralrealms.skyblock.action.island.ban;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;

/**
 * Dialog action: {@code [ban-member] <island> <target> [reason...]}. The reason is optional and takes
 * the rest of the line. Permission checks live in the service.
 */
public record BanMemberAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UUID> targetUuid,
        PlaceholderWrapper<String> reason
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        UUID target = context.parseWrapper(this.targetUuid);
        String reason = this.reason == null ? null : context.parseWrapper(this.reason);
        AstralSkyblock.get().bans().ban(island, player, target, reason == null || reason.isBlank() ? null : reason);
    }
}
