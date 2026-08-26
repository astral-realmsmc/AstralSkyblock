package com.astralrealms.skyblock.model.upgrade;

import java.util.Map;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.core.paper.model.action.PaperActionList;
import com.astralrealms.core.paper.model.itemstack.ItemStackWrapper;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.core.placeholder.wrapper.impl.component.ComponentWrapper;
import com.astralrealms.core.provider.ItemProvider;

@ConfigSerializable
public record IslandUpgrade(UpgradeType type, ItemStackWrapper icon, Map<Integer, Level> levels) implements ComplexPlaceholder {

    public int maxLevel() {
        return levels.keySet()
                .stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return this;

        return switch (context.next()) {
            case "type" -> type;
            case "icon" -> icon;
            case "levels" -> ItemProvider.of(levels.values());
            case "level" -> {
                if (!context.hasNext())
                    yield null;
                yield levels.get(context.next());
            }
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "upgrade";
    }

    /**
     * One purchasable step of an upgrade.
     *
     * @param value the effect magnitude of this level — a border diameter, a member cap, ...
     * @param key   an optional identifier the effect resolves against (currently the generator
     *              blueprint id used by {@link UpgradeType#GENERATOR}); unused by other types
     */
    @ConfigSerializable
    public record Level(int level, double price, String currency, ComponentWrapper currencyDisplay,
                        double value, String key, PaperActionList unlockActions) implements ComplexPlaceholder {

        @Override
        public Object get(PlaceholderContext context) {
            if (!context.hasNext())
                return this;

            return switch (context.next()) {
                case "level" -> level;
                case "price" -> price;
                case "currency" -> currency;
                case "currencyDisplay" -> currencyDisplay == null ? currency : currencyDisplay.get(context.function());
                case "value" -> value;
                case "intValue" -> (int) value;
                case "key" -> key;
                case null, default -> null;
            };
        }

        @Override
        public String namespace() {
            return "level";
        }
    }
}
