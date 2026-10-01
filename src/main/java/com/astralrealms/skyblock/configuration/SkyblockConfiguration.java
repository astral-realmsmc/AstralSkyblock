package com.astralrealms.skyblock.configuration;

import java.util.List;
import java.util.Set;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.model.island.IslandSettings;

@ConfigSerializable
public record SkyblockConfiguration(int maximumIslands, String islandsGroup, int worldIdleUnloadSeconds,
                                    String fallbackGroup, int maximumWarps, int maximumRoles, Defaults defaults, Level level,
                                    Set<IslandSettings> defaultSettings, Generators generators, List<String> allowedBiomes,
                                    String ownerRoleName, Boolean voidTeleport) {

    /** Biomes refused by /is biome unless listed in {@code allowed-biomes}: they change what spawns. */
    private static final Set<String> NON_OVERWORLD_BIOMES = Set.of(
            "minecraft:nether_wastes", "minecraft:soul_sand_valley", "minecraft:crimson_forest",
            "minecraft:warped_forest", "minecraft:basalt_deltas", "minecraft:the_end",
            "minecraft:end_highlands", "minecraft:end_midlands", "minecraft:small_end_islands",
            "minecraft:end_barrens", "minecraft:the_void");

    /**
     * Whether players may paint their island with the biome {@code key} ({@code minecraft:plains}).
     * An empty or absent {@code allowed-biomes} allows every overworld biome; nether, end and void
     * biomes (ghast and piglin spawns on an overworld island) only when listed.
     */
    public boolean isBiomeAllowed(String key) {
        if (this.allowedBiomes != null && !this.allowedBiomes.isEmpty())
            return this.allowedBiomes.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(key)
                                                                   || ("minecraft:" + allowed).equalsIgnoreCase(key));
        return !NON_OVERWORLD_BIOMES.contains(key);
    }

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

    /** Maximum number of warps an island may define. {@code 0}/absent falls back to 1. */
    @Override
    public int maximumWarps() {
        return this.maximumWarps <= 0 ? 1 : this.maximumWarps;
    }

    /** Maximum number of custom member roles an island may define. {@code 0}/absent falls back to 10. */
    @Override
    public int maximumRoles() {
        return this.maximumRoles <= 0 ? 10 : this.maximumRoles;
    }

    /** Whether players falling into the void on an island are sent back to its spawn. Defaults to on. */
    @Override
    public Boolean voidTeleport() {
        return this.voidTeleport == null || this.voidTeleport;
    }

    /** Rank shown for an island's owner in member menus — the owner holds no role. */
    @Override
    public String ownerRoleName() {
        return this.ownerRoleName == null || this.ownerRoleName.isBlank() ? "Owner" : this.ownerRoleName;
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

    /** Hopper cap of an island with no {@code HOPPERS_LIMIT} upgrade configured. */
    public int defaultHopperLimit() {
        return defaults().hopperLimit();
    }

    /** Minecart cap of an island with no {@code MINECART_LIMITS} upgrade configured. */
    public int defaultMinecartLimit() {
        return defaults().minecartLimit();
    }

    /** Crop growth multiplier of an island with no {@code CROP_GROWTH_SPEED} upgrade configured. */
    public double defaultCropGrowthMultiplier() {
        return defaults().cropGrowthMultiplier();
    }

    /** Spawner rate multiplier of an island with no {@code SPAWNERS_RATE} upgrade configured. */
    public double defaultSpawnerRateMultiplier() {
        return defaults().spawnerRateMultiplier();
    }

    /** Mob drop multiplier of an island with no {@code MOB_DROPS} upgrade configured. */
    public double defaultMobDropsMultiplier() {
        return defaults().mobDropsMultiplier();
    }

    /**
     * Baseline values used when an upgrade has no blueprint at all — an island always has a member
     * cap and a border, whether or not the corresponding upgrade is configured.
     *
     * <p>The multipliers are floored at {@code 1} rather than at some positive value: an absent or
     * zero entry means "no bonus", and a configuration that slowed crops or thinned drops below
     * vanilla would be a misconfiguration rather than an upgrade.
     */
    @ConfigSerializable
    public record Defaults(int memberLimit, int coopLimit, double worldBorderSize,
                           int hopperLimit, int minecartLimit, double cropGrowthMultiplier,
                           double spawnerRateMultiplier, double mobDropsMultiplier) {

        private static final Defaults FALLBACK = new Defaults(0, 0, 0, 0, 0, 0, 0, 0);

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

        @Override
        public int hopperLimit() {
            return this.hopperLimit <= 0 ? 32 : this.hopperLimit;
        }

        @Override
        public int minecartLimit() {
            return this.minecartLimit <= 0 ? 16 : this.minecartLimit;
        }

        @Override
        public double cropGrowthMultiplier() {
            return Math.max(1, this.cropGrowthMultiplier);
        }

        @Override
        public double spawnerRateMultiplier() {
            return Math.max(1, this.spawnerRateMultiplier);
        }

        @Override
        public double mobDropsMultiplier() {
            return Math.max(1, this.mobDropsMultiplier);
        }
    }

    /**
     * Island scoring. An island's {@code value} is the sum of its blocks' configured worth; its
     * {@code level} is that value divided by {@link #pointsPerLevel()}.
     */
    @ConfigSerializable
    public record Level(int pointsPerLevel, int chunksPerBatch, int rescanIntervalSeconds,
                        int cooldownSeconds, int topSize, int topRefreshSeconds, int valuableThreshold) {

        private static final Level FALLBACK = new Level(0, 0, 0, 0, 0, 0, 0);

        /**
         * Minimum block value that makes breaking a block need {@code VALUABLE_BREAK} rather than
         * plain {@code BREAK}. Without one, cobblestone (worth 1) counted as valuable and a coop
         * could not mine the island's generator.
         */
        @Override
        public int valuableThreshold() {
            return this.valuableThreshold <= 0 ? 10 : this.valuableThreshold;
        }

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
