package com.astralrealms.skyblock.placeholder.settings;

import com.astralrealms.core.placeholder.Placeholder;
import com.astralrealms.core.provider.ItemProvider;
import com.astralrealms.skyblock.model.IslandSettings;
import com.astralrealms.skyblock.model.island.Island;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IslandSettingsItemProvider implements ItemProvider {

    private static final int LENGTH = IslandSettings.values().length;
    private final Island island;


    @Override
    public Placeholder item(int i) {
        IslandSettings setting = IslandSettings.values()[i];
        return new IslandSettingsPlaceholder(island, setting);
    }

    @Override
    public int size() {
        return LENGTH;
    }
}
