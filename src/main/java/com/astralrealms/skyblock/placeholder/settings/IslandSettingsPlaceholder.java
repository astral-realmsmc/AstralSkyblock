package com.astralrealms.skyblock.placeholder.settings;

import com.astralrealms.core.paper.placeholder.itemstack.ItemStackPlaceholder;
import com.astralrealms.core.placeholder.PlaceholderContext;
import com.astralrealms.core.placeholder.impl.system.ComplexPlaceholder;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandSettingsPlaceholder implements ComplexPlaceholder {

    private final Island island;
    private final IslandSettings setting;

    @Override
    public Object get(PlaceholderContext context) {
        if (!context.hasNext())
            return setting;

        return switch (context.next()) {
            case "id" -> setting.name();
            case "item" -> new ItemStackPlaceholder(this.setting.value().get(context.function()));
            case "enabled" -> island.isSettingEnabled(setting);
            case null, default -> null;
        };
    }

    @Override
    public String namespace() {
        return "setting";
    }

}
