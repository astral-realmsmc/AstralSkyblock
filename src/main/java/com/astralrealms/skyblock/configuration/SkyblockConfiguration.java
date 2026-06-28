package com.astralrealms.skyblock.configuration;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup) {
}
