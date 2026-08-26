package com.astralrealms.skyblock.configuration;

import java.util.Set;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.model.island.IslandSettings;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup, int worldIdleUnloadSeconds,
                                    Set<IslandSettings> defaultSettings, Generators generators) {

    public boolean isIslandServer() {
        return this.islandsGroup.equals(AstralPaperAPI.serverInformation().group());
    }

    /**
     * Seconds an island world may stay loaded with no players before it is unloaded by the idle sweep.
     * {@code 0}/absent falls back to a 5 minute default; a negative value disables idle unloading entirely.
     */
    @Override
    public int worldIdleUnloadSeconds() {
        return this.worldIdleUnloadSeconds == 0 ? 300 : this.worldIdleUnloadSeconds;
    }

    @ConfigSerializable
    public record Generators(boolean enabled, @Setting("default") String defaultGenerator) {
    }
}
