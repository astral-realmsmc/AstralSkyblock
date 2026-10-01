package com.astralrealms.skyblock.action.island.coop;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;

/**
 * Dialog action: {@code [coop-player] <island> <player name>}. Sends a coop invitation for
 * the island to the player typed in the dialog; resolving the name and the checks live in the service.
 */
public record CoopPlayerAction(PlaceholderWrapper<Island> island, PlaceholderWrapper<String> name) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        AstralSkyblock.get()
                .invitations()
                .inviteByName(context.parseWrapper(this.island), player, context.parseWrapper(this.name), InvitationType.COOP);
    }
}
