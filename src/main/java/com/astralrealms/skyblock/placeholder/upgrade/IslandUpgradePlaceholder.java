package com.astralrealms.skyblock.placeholder.upgrade;

import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;

import lombok.RequiredArgsConstructor;

/**
 * One upgrade seen from an island: the configured blueprint joined with that island's current
 * level, so a menu can render "level 3 / 5 — next: 4 000 coins" without resolving two placeholders
 * against each other.
 */
@RequiredArgsConstructor
public class IslandUpgradePlaceholder implements ComplexPlaceholder {

    private final Island island;
    private final IslandUpgrade blueprint;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return blueprint.type().name();

        int current = island.upgradeLevel(blueprint.type());
        IslandUpgrade.Level next = blueprint.levels().get(current + 1);

        return switch (context.next()) {
            case "type" -> blueprint.type().name();
            case "blueprint" -> blueprint;
            case "level" -> current;
            case "maxLevel" -> blueprint.maxLevel();
            case "isMax" -> next == null;
            case "value" -> currentValue(current);
            case "next" -> next;
            case "nextLevel" -> next == null ? current : next.level();
            case "nextValue" -> next == null ? currentValue(current) : next.value();
            case "price" -> next == null ? 0D : next.price();
            case "currency" -> next == null ? "" : next.currency();
            case "currencyDisplay" -> {
                if (next == null)
                    yield "";
                yield next.currencyDisplay() == null ? next.currency() : next.currencyDisplay().get(context.function());
            }
            case null, default -> null;
        };
    }

    private double currentValue(int current) {
        IslandUpgrade.Level level = blueprint.levels().get(current);
        return level == null ? 0D : level.value();
    }

    @Override
    public String namespace() {
        return "upgrade";
    }
}
