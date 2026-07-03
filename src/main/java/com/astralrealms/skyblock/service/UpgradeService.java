package com.astralrealms.skyblock.service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.astralrealms.skyblock.repository.UpgradeRepository;

public class UpgradeService {

    private final AstralSkyblock plugin;
    private final UpgradeRepository repository;
    private final Map<UpgradeType, IslandUpgrade> blueprints = new HashMap<>();

    public UpgradeService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new UpgradeRepository(plugin);
        this.load();
    }

    public void load() {
        this.plugin.getSLF4JLogger().info("Loading island upgrade blueprints...");
        this.blueprints.clear();

        Path dataPath = this.plugin.getDataPath().resolve("upgrades");
        Collection<IslandUpgrade> upgrades = this.plugin.configurationManager().loadFolder(dataPath, IslandUpgrade.class);
        for (IslandUpgrade upgrade : upgrades) {
            if (this.blueprints.containsKey(upgrade.type())) {
                this.plugin.getSLF4JLogger().warn("Duplicate island upgrade blueprint found for type: {}", upgrade.type());
                continue;
            }

            this.blueprints.put(upgrade.type(), upgrade);
        }

        this.plugin.getSLF4JLogger().info("Loaded {} island upgrade blueprints.", this.blueprints.size());
    }

    public UpgradeRepository repository() {
        return this.repository;
    }

    // =====================================================================================
    //  Persisted levels
    // =====================================================================================

    /**
     * All of an island's stored upgrade levels, keyed by their {@link UpgradeType}. Primes the
     * island's slice in the repository, making {@link #level(UUID, UpgradeType)} accurate for it.
     * Absent types are at level 0 (the override-only default); stale stored keys no longer backed
     * by the enum are logged and skipped.
     */
    public CompletableFuture<Map<UpgradeType, Integer>> findByIsland(UUID islandId) {
        return this.repository.findByIsland(islandId)
                .thenApply(upgrades -> {
                    Map<UpgradeType, Integer> levels = new EnumMap<>(UpgradeType.class);
                    for (com.astralrealms.skyblock.model.island.IslandUpgrade upgrade : upgrades)
                        parseType(upgrade.upgrade()).ifPresent(type -> levels.put(type, upgrade.level()));
                    return levels;
                });
    }

    /**
     * O(1) cached lookup of an island's level for an upgrade; 0 when never purchased. The island
     * cascade primes the slice for every cached island — for one already in hand, prefer
     * {@link com.astralrealms.skyblock.model.island.Island#upgradeLevel(UpgradeType)}.
     */
    public int level(UUID islandId, UpgradeType type) {
        return this.repository.level(islandId, type.name());
    }

    /**
     * Persists an island's new level for an upgrade, rebuilds the local island's upgrade snapshot,
     * and broadcasts the change so other servers refresh theirs. Returns the stored level.
     */
    public CompletableFuture<Integer> setLevel(UUID islandId, UpgradeType type, int level) {
        return this.repository.setLevel(islandId, type.name(), level)
                .thenCompose(saved -> this.plugin.islands()
                        .refreshUpgrades(islandId)
                        .thenApply(ignored -> saved.level()));
    }

    // =====================================================================================
    //  Blueprints (configuration)
    // =====================================================================================

    public Optional<IslandUpgrade> findByType(UpgradeType type) {
        return Optional.ofNullable(this.blueprints.get(type));
    }

    @Unmodifiable
    public Collection<IslandUpgrade> blueprints() {
        return this.blueprints.values();
    }

    /**
     * Maps a stored upgrade key back to its enum, dropping (with a warning) any stale key.
     */
    private Optional<UpgradeType> parseType(String key) {
        if (key == null || key.isBlank())
            return Optional.empty();
        try {
            return Optional.of(UpgradeType.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            this.plugin.getSLF4JLogger().warn("Unknown island upgrade '{}' stored in island_upgrades; ignoring.", key);
            return Optional.empty();
        }
    }
}
