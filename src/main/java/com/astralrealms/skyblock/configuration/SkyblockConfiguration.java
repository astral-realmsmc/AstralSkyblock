package com.astralrealms.skyblock.configuration;

import java.util.Set;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.model.island.IslandSettings;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup, int worldIdleUnloadSeconds,
                                    String fallbackGroup, int maximumWarps, Defaults defaults,
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

    /** Server group a player is sent to when they are evicted from an island (a ban). */
    @Override
    public String fallbackGroup() {
        return this.fallbackGroup == null || this.fallbackGroup.isBlank() ? "hub" : this.fallbackGroup;
    }

    /** Maximum number of warps an island may define. {@code 0}/absent falls back to 5. */
    @Override
    public int maximumWarps() {
        return this.maximumWarps <= 0 ? 5 : this.maximumWarps;
    }

    @Override
    public Defaults defaults() {
        return this.defaults == null ? Defaults.FALLBACK : this.defaults;
    }

    /** Member cap of an island with no {@code MEMBERS_LIMIT} upgrade configured. */
    public int defaultMemberLimit() {
        return defaults().memberLimit();
    }

    /** Coop cap of an island with no {@code COOP_LIMIT} upgrade configured. */
    public int defaultCoopLimit() {
        return defaults().coopLimit();
    }

    /** Border diameter of an island with no {@code WORLDBORDER_SIZE} upgrade configured. */
    public double defaultWorldBorderSize() {
        return defaults().worldBorderSize();
    }

    /**
     * Baseline values used when an upgrade has no blueprint at all — an island always has a member
     * cap and a border, whether or not the corresponding upgrade is configured.
     */
    @ConfigSerializable
    public record Defaults(int memberLimit, int coopLimit, double worldBorderSize) {

        private static final Defaults FALLBACK = new Defaults(0, 0, 0);

        @Override
        public int memberLimit() {
            return this.memberLimit <= 0 ? 5 : this.memberLimit;
        }

        @Override
        public int coopLimit() {
            return this.coopLimit <= 0 ? 5 : this.coopLimit;
        }

        @Override
        public double worldBorderSize() {
            return this.worldBorderSize <= 0 ? 100 : this.worldBorderSize;
        }
    }

    @ConfigSerializable
    public record Generators(boolean enabled, @Setting("default") String defaultGenerator) {
    }
}
