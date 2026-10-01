package com.astralrealms.skyblock.action.island.member;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;

/**
 * Dialog action: {@code [invite-member] <island> <player name>}. Sends an invitation to join
 * the island to the player typed in the dialog; resolving the name and the checks live in the service.
 */
public record InviteMemberAction(PlaceholderWrapper<Island> island, PlaceholderWrapper<String> name) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        AstralSkyblock.get()
                .invitations()
                .inviteByName(context.parseWrapper(this.island), player, context.parseWrapper(this.name), InvitationType.MEMBER);
    }
}
