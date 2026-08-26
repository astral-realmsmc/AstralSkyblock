package com.astralrealms.skyblock.configuration;

import java.util.Set;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.model.island.IslandSettings;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup, int worldIdleUnloadSeconds,
                                    String fallbackGroup, int maximumWarps, Defaults defaults, Level level,
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
    public Level level() {
        return this.level == null ? Level.FALLBACK : this.level;
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

    /**
     * Island scoring. An island's {@code value} is the sum of its blocks' configured worth; its
     * {@code level} is that value divided by {@link #pointsPerLevel()}.
     */
    @ConfigSerializable
    public record Level(int pointsPerLevel, int chunksPerBatch, int rescanIntervalSeconds,
                        int cooldownSeconds, int topSize, int topRefreshSeconds) {

        private static final Level FALLBACK = new Level(0, 0, 0, 0, 0, 0);

        /** Block value one island level is worth. */
        @Override
        public int pointsPerLevel() {
            return this.pointsPerLevel <= 0 ? 100 : this.pointsPerLevel;
        }

        /** Chunks snapshotted per tick while scanning — higher scans faster but costs more per tick. */
        @Override
        public int chunksPerBatch() {
            return this.chunksPerBatch <= 0 ? 4 : this.chunksPerBatch;
        }

        /**
         * Seconds between automatic rescans of the islands hosted by this server. A negative value
         * disables them, leaving scoring to {@code /is calc} and island world loads.
         */
        @Override
        public int rescanIntervalSeconds() {
            return this.rescanIntervalSeconds == 0 ? 900 : this.rescanIntervalSeconds;
        }

        /** Seconds a player must wait between two on-demand rescans of the same island. */
        @Override
        public int cooldownSeconds() {
            return this.cooldownSeconds < 0 ? 0 : (this.cooldownSeconds == 0 ? 60 : this.cooldownSeconds);
        }

        /** How many islands the leaderboard holds. */
        @Override
        public int topSize() {
            return this.topSize <= 0 ? 50 : this.topSize;
        }

        /** Seconds between leaderboard refreshes. */
        @Override
        public int topRefreshSeconds() {
            return this.topRefreshSeconds <= 0 ? 300 : this.topRefreshSeconds;
        }
    }

    @ConfigSerializable
    public record Generators(boolean enabled, @Setting("default") String defaultGenerator) {
    }
}
