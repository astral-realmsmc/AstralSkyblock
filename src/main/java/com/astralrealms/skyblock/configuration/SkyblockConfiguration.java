package com.astralrealms.skyblock.configuration;

import java.util.Set;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import com.astralrealms.skyblock.model.island.IslandSettings;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup, Set<IslandSettings> defaultSettings) {
}
