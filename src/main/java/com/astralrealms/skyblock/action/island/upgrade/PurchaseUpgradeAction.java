package com.astralrealms.skyblock.action.island.upgrade;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

/** Menu action: {@code [purchase-upgrade] <island> <upgrade type>}. */
public record PurchaseUpgradeAction(
        PlaceholderWrapper<Island> island,
        PlaceholderWrapper<UpgradeType> type
) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        Island island = context.parseWrapper(this.island);
        UpgradeType type = context.parseWrapper(this.type);
        AstralSkyblock.get().upgrades().purchase(island, player, type);
    }
}
