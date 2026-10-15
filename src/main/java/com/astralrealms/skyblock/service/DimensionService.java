package com.astralrealms.skyblock.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.location.MinecraftLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.configuration.DimensionsConfiguration;
import com.astralrealms.skyblock.model.dimension.DimensionPortal;
import com.astralrealms.skyblock.model.dimension.IslandDimension;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

/**
 * The dimensions declared in {@code dimensions.yml} and the rules for entering them: the island must
 * have unlocked the dimension through its {@link UpgradeType#DIMENSIONS} upgrade, and the player
 * entering must hold the dimension's permission. Staff ({@code skyblock.admin}) skip both.
 *
 * <p>The worlds themselves are handled by {@link WorldService}; this only says which exist and who
 * may go where.
 */
public class DimensionService {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private final AstralSkyblock plugin;
    private volatile DimensionsConfiguration configuration = DimensionsConfiguration.DISABLED;
    private volatile Map<String, IslandDimension> dimensions = Map.of();

    public DimensionService(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    public void load() {
        DimensionsConfiguration loaded = this.plugin.loadConfiguration("dimensions.yml", DimensionsConfiguration.class);
        this.configuration = loaded == null ? DimensionsConfiguration.DISABLED : loaded;
        if (!this.configuration.enabled()) {
            this.dimensions = Map.of();
            this.plugin.getSLF4JLogger().info("Island dimensions are disabled.");
            return;
        }

        Path sourceWorlds = this.plugin.getDataPath().resolve("sourceWorlds");
        this.dimensions = validate(this.configuration.dimensions(),
                sourceWorld -> Files.exists(sourceWorlds.resolve(sourceWorld)),
                warning -> this.plugin.getSLF4JLogger().warn(warning));
        this.plugin.getSLF4JLogger().info("Loaded {} island dimension(s): {}", this.dimensions.size(), this.dimensions.keySet());
    }

    /**
     * Checks the generator and unlock references of the dimensions, once every other configuration
     * is loaded. Only warns: a dimension nobody can unlock is still a valid one.
     */
    public void checkReferences() {
        if (!enabled())
            return;
        Set<String> unlockable = this.plugin.upgrades()
                .findByType(UpgradeType.DIMENSIONS)
                .map(blueprint -> unlockedBy(blueprint, Integer.MAX_VALUE))
                .orElse(Set.of());
        for (IslandDimension dimension : this.dimensions.values()) {
            if (!unlockable.contains(dimension.id()))
                this.plugin.getSLF4JLogger().warn("Dimension '{}' is not unlocked by any level of the DIMENSIONS upgrade; "
                                                  + "only staff can enter it", dimension.id());
            if (dimension.generators() != null)
                dimension.generators().values().stream()
                        .filter(generator -> this.plugin.generators().findById(generator).isEmpty())
                        .forEach(generator -> this.plugin.getSLF4JLogger().warn("Dimension '{}' overrides generators with unknown generator '{}'",
                                dimension.id(), generator));
        }
    }

    /**
     * The dimensions of {@code declared} that can actually be used, keyed and filled in with their
     * id. Anything wrong with one drops it, with a warning, rather than the whole file.
     */
    static Map<String, IslandDimension> validate(Map<String, IslandDimension> declared, Predicate<String> sourceExists,
                                                 Consumer<String> warnings) {
        Map<String, IslandDimension> valid = new LinkedHashMap<>();
        Map<DimensionPortal, String> portals = new EnumMap<>(DimensionPortal.class);
        for (Map.Entry<String, IslandDimension> entry : declared.entrySet()) {
            String id = entry.getKey();
            IslandDimension dimension = entry.getValue();
            if (dimension == null)
                continue;
            if (!ID_PATTERN.matcher(id).matches() || id.equals(IslandDimension.OVERWORLD)) {
                warnings.accept("Dimension '" + id + "' skipped: ids are 1-32 lowercase letters, digits, '_' or '-', and '"
                                + IslandDimension.OVERWORLD + "' is reserved");
                continue;
            }
            if (dimension.sourceWorld() == null || dimension.sourceWorld().isBlank() || dimension.spawnLocation() == null) {
                warnings.accept("Dimension '" + id + "' skipped: it needs a 'source-world' and a 'spawn-location'");
                continue;
            }
            if (!sourceExists.test(dimension.sourceWorld())) {
                warnings.accept("Dimension '" + id + "' skipped: source world " + dimension.sourceWorld() + " not found in sourceWorlds/");
                continue;
            }
            DimensionPortal portal = dimension.portal();
            if (portal != DimensionPortal.NONE && portals.containsKey(portal)) {
                warnings.accept("Dimension '" + id + "' skipped: the " + portal + " portal already leads to '" + portals.get(portal) + "'");
                continue;
            }
            if (portal != DimensionPortal.NONE)
                portals.put(portal, id);
            valid.put(id, dimension.withId(id));
        }
        return valid;
    }

    // =========================================================================
    //  Registry
    // =========================================================================

    public boolean enabled() {
        return this.configuration.enabled();
    }

    public DimensionsConfiguration configuration() {
        return this.configuration;
    }

    /** A declared dimension, if the feature is on. */
    public Optional<IslandDimension> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(this.dimensions.get(id));
    }

    @Unmodifiable
    public Collection<IslandDimension> all() {
        return List.copyOf(this.dimensions.values());
    }

    // =========================================================================
    //  Access
    // =========================================================================

    /** The ids of the dimensions this island has unlocked. */
    public Set<String> unlocked(Island island) {
        return this.plugin.upgrades()
                .findByType(UpgradeType.DIMENSIONS)
                .map(blueprint -> unlockedBy(blueprint, island.upgradeLevel(UpgradeType.DIMENSIONS)))
                .orElse(Set.of());
    }

    public boolean isUnlocked(Island island, String dimension) {
        return unlocked(island).contains(dimension);
    }

    /** The dimensions named by every level of the upgrade at or below {@code level}. */
    static Set<String> unlockedBy(@Nullable IslandUpgrade blueprint, int level) {
        Set<String> unlocked = new LinkedHashSet<>();
        if (blueprint == null || blueprint.levels() == null)
            return unlocked;
        blueprint.levels().entrySet().stream()
                .filter(entry -> entry.getKey() <= level)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().key())
                .filter(key -> key != null && !key.isBlank())
                .forEach(unlocked::add);
        return unlocked;
    }

    /**
     * Why this player may not enter this island's dimension, or {@code null} when they may. Bans and
     * closed islands are not checked here: they apply to every island world alike.
     */
    @Nullable
    public ASMessages denial(Player player, Island island, IslandDimension dimension) {
        if (!enabled())
            return ASMessages.DIMENSIONS_DISABLED;
        if (player.hasPermission("skyblock.admin"))
            return null;
        if (!isUnlocked(island, dimension.id()))
            return ASMessages.DIMENSION_LOCKED;
        if (!player.hasPermission(dimension.permission()))
            return ASMessages.DIMENSION_NO_PERMISSION;
        return null;
    }

    /**
     * Where a portal of type {@code portal} leads from {@code current} (a dimension id, or
     * {@code null} for the overworld): {@link IslandDimension#OVERWORLD}, a dimension id, or
     * {@code null} when it leads nowhere.
     *
     * <p>From the overworld it leads to the dimension declaring that portal. Inside a dimension, the
     * dimension's own portal leads back home, and any other portal to the dimension declaring it.
     */
    @Nullable
    static String route(@Nullable String current, @Nullable DimensionPortal portal, Collection<IslandDimension> dimensions) {
        if (portal == null || portal == DimensionPortal.NONE)
            return null;

        if (current != null) {
            IslandDimension here = dimensions.stream().filter(dimension -> dimension.id().equals(current)).findFirst().orElse(null);
            if (here != null && here.portal() == portal)
                return IslandDimension.OVERWORLD;
        }
        return dimensions.stream()
                .filter(dimension -> dimension.portal() == portal && !dimension.id().equals(current))
                .map(IslandDimension::id)
                .findFirst()
                .orElse(null);
    }

    /** Where a portal leads from {@code current}, against the dimensions declared here. */
    @Nullable
    public String route(@Nullable String current, @Nullable DimensionPortal portal) {
        return route(current, portal, this.dimensions.values());
    }

    /** Where players arrive in a dimension's world. */
    public Location spawn(IslandDimension dimension, World world) {
        MinecraftLocation spawn = dimension.spawnLocation();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    /**
     * Sends home everyone standing in one of the island's dimensions that they may no longer enter —
     * after its upgrade level was lowered. Main thread; a no-op when the island is not hosted here.
     */
    public void enforceAccess(Island island) {
        if (!enabled())
            return;
        World home = this.plugin.worlds().findByIslandId(island.uniqueId()).map(instance -> instance.getBukkitWorld()).orElse(null);
        if (home == null)
            return;

        this.plugin.worlds().dimensionWorlds(island.uniqueId()).forEach((id, world) -> {
            IslandDimension dimension = this.dimensions.get(id);
            for (Player player : List.copyOf(world.getPlayers())) {
                if (dimension != null && denial(player, island, dimension) == null)
                    continue;
                player.teleportAsync(island.spawnLocation(home));
                ASMessages.DIMENSION_ACCESS_LOST.message(player, AstralPaperAPI.createPlaceholderContainer(player)
                        .registerPlaceholder(island)
                        .registerDirect("dimension", id)
                        .registerDirect("dimension_name", dimension == null ? id : dimension.displayName()));
            }
        });
    }
}
