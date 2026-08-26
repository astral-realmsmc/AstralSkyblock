package com.astralrealms.skyblock.action.island.create;

import org.bukkit.entity.Player;

import com.astralrealms.core.paper.model.action.PaperAction;
import com.astralrealms.core.paper.model.action.PaperActionContext;
import com.astralrealms.core.placeholder.wrapper.PlaceholderWrapper;
import com.astralrealms.core.platform.executable.exception.ExecutableRunException;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.IslandBlueprint;

/**
 * Menu action: {@code [create-island] <blueprint id>} — creates the clicking player's island from
 * the chosen blueprint, named after them.
 */
public record CreateIslandAction(PlaceholderWrapper<String> blueprintId) implements PaperAction {

    @Override
    public void run(PaperActionContext context) throws ExecutableRunException {
        Player player = context.executor();
        String id = context.parseWrapper(this.blueprintId);

        AstralSkyblock plugin = AstralSkyblock.get();
        IslandBlueprint blueprint = plugin.blueprints().findById(id).orElse(null);
        if (blueprint == null) {
            plugin.getSLF4JLogger().warn("{} clicked a creation icon for unknown blueprint '{}'", player.getName(), id);
            ASMessages.UNEXPECTED_ERROR.message(player);
            return;
        }

        plugin.islands().create(player, null, blueprint);
    }
}
