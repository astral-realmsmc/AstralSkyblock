package com.astralrealms.skyblock.service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.EconomyService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.configuration.GeneratorConfiguration;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.upgrade.IslandUpgrade;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.astralrealms.skyblock.repository.UpgradeRepository;

public class UpgradeService {

    private final AstralSkyblock plugin;
    private final UpgradeRepository repository;
    private final Map<UpgradeType, IslandUpgrade> blueprints = new HashMap<>();
    // Islands with a purchase in flight, so a double click cannot be charged twice.
    private final Set<UUID> purchasing = ConcurrentHashMap.newKeySet();

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
     * Buys one level of an upgrade, but only if the island is still at {@code expectedLevel} —
     * the check is a conditional {@code UPDATE}, so a simultaneous purchase on another server
     * cannot also succeed. Refreshes the island's upgrade snapshot when it wins.
     *
     * @return whether this call advanced the level
     */
    public CompletableFuture<Boolean> advance(UUID islandId, UpgradeType type, int expectedLevel) {
        return this.repository.advanceLevel(islandId, type.name(), expectedLevel)
                .thenCompose(advanced -> {
                    if (!Boolean.TRUE.equals(advanced))
                        return CompletableFuture.completedFuture(false);

                    // The level is committed at this point. A failed snapshot refresh is a display
                    // problem — surfacing it as a failed advance would have the caller refund a
                    // level the island already owns, so it is logged and swallowed instead.
                    return this.plugin.islands()
                            .refreshUpgrades(islandId)
                            .exceptionally(throwable -> {
                                this.plugin.getSLF4JLogger().error("Upgrade {} of island {} was advanced but its snapshot "
                                                                   + "could not be refreshed", type, islandId, throwable);
                                return null;
                            })
                            .thenApply(ignored -> true);
                });
    }

    /**
     * Persists an island's new level for an upgrade, rebuilds the local island's upgrade snapshot,
     * and broadcasts the change so other servers refresh theirs. Returns the stored level.
     *
     * <p>Unconditional — for administrative use. Purchases go through {@link #advance} instead.
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

    // =====================================================================================
    //  Purchasing
    // =====================================================================================

    /**
     * Buys the next level of an upgrade for an island. The buyer must hold
     * {@link IslandPermission#RANKUP}; the cost is withdrawn from their own balance in the level's
     * configured currency (the network default when the level names none). On success the level is
     * persisted and published, the level's unlock actions run, and the new level's effect is applied.
     *
     * <p>Concurrent purchases for the same island are rejected rather than queued — two clicks in
     * the same tick must not buy (and charge for) two levels.
     */
    public CompletableFuture<Void> purchase(Island island, Player player, UpgradeType type) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island)
                .registerDirect("upgrade_type", type.name());

        if (!island.hasPermission(player, IslandPermission.RANKUP)) {
            ASMessages.NO_PERMISSION.message(player);
            return CompletableFuture.completedFuture(null);
        }

        IslandUpgrade blueprint = this.blueprints.get(type);
        if (blueprint == null) {
            ASMessages.UPGRADE_NOT_FOUND.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        placeholders.registerPlaceholder(blueprint);

        int nextLevel = island.upgradeLevel(type) + 1;
        IslandUpgrade.Level level = blueprint.levels().get(nextLevel);
        if (level == null) {
            ASMessages.UPGRADE_MAX_LEVEL.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        placeholders.registerPlaceholder(level);

        // Cheap local fast-path against a double click; the authoritative guard is the conditional
        // level update in advance(), which also covers two servers racing each other.
        if (!this.purchasing.add(island.uniqueId())) {
            ASMessages.UPGRADE_IN_PROGRESS.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        EconomyService economy = AstralPaperAPI.getService(EconomyService.class).orElseThrow();
        String currency = level.currency() == null || level.currency().isBlank() ? null : level.currency();
        return hasBalance(economy, player.getUniqueId(), currency, level.price())
                .thenCompose(affordable -> {
                    if (!Boolean.TRUE.equals(affordable)) {
                        ASMessages.UPGRADE_INSUFFICIENT_FUNDS.message(player, placeholders);
                        return CompletableFuture.completedFuture(null);
                    }

                    return withdraw(economy, player.getUniqueId(), currency, level.price())
                            .thenCompose(charged -> {
                                if (!Boolean.TRUE.equals(charged)) {
                                    ASMessages.UPGRADE_INSUFFICIENT_FUNDS.message(player, placeholders);
                                    return CompletableFuture.completedFuture(null);
                                }
                                return apply(island, player, type, nextLevel, level, placeholders, economy, currency);
                            });
                })
                .whenComplete((ignored, throwable) -> {
                    this.purchasing.remove(island.uniqueId());
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to purchase upgrade {} for island {}", type, island.uniqueId(), throwable);
                    }
                });
    }

    /**
     * Persists the purchased level and applies it: effects, unlock actions and the confirmation
     * message, all on the main thread. The player is already charged at this point, so a failed
     * write is refunded before the failure propagates.
     */
    private CompletableFuture<Void> apply(Island island, Player player, UpgradeType type, int nextLevel,
                                          IslandUpgrade.Level level, PlaceholderContainer placeholders,
                                          EconomyService economy, String currency) {
        // The purchase was priced against the level the island held, which is what the conditional
        // update must still find for this call to be the one that advances it.
        return advance(island.uniqueId(), type, nextLevel - 1)
                .thenCompose(advanced -> {
                    if (!Boolean.TRUE.equals(advanced))
                        // Somebody bought this level first — on this server or another one. Give the
                        // money back rather than charging twice for a single level, and pull in the
                        // level they actually bought: until this server's snapshot catches up, every
                        // retry would price the same level again and bounce off the same conflict.
                        return this.plugin.islands()
                                .refreshUpgrades(island.uniqueId())
                                .exceptionally(throwable -> {
                                    this.plugin.getSLF4JLogger().error("Failed to refresh the upgrades of island {} after a "
                                                                       + "lost purchase race", island.uniqueId(), throwable);
                                    return null;
                                })
                                .thenCompose(ignored -> refund(economy, player.getUniqueId(), currency, level.price()))
                                .thenRun(() -> notify(player, ASMessages.UPGRADE_LEVEL_CHANGED, placeholders));

                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        applyEffects(island, type);
                        if (level.unlockActions() != null)
                            runUnlockActions(level, player);
                        ASMessages.UPGRADE_PURCHASED.message(player, placeholders);
                    });
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .exceptionallyCompose(throwable -> refund(economy, player.getUniqueId(), currency, level.price())
                        .thenCompose(ignored -> CompletableFuture.failedFuture(throwable)));
    }

    /**
     * Sends a message without letting a failure escape into the purchase chain. Anything thrown
     * after the refund — a placeholder that cannot resolve, a player who left — would otherwise
     * reach the compensating {@code exceptionallyCompose} and refund the same charge twice.
     */
    private void notify(Player player, ASMessages message, PlaceholderContainer placeholders) {
        try {
            message.message(player, placeholders);
        } catch (Exception exception) {
            this.plugin.getSLF4JLogger().warn("Failed to send {} to {}", message.name(), player.getName(), exception);
        }
    }

    private CompletableFuture<Boolean> hasBalance(EconomyService economy, UUID playerUuid, String currency, double price) {
        if (price <= 0)
            return CompletableFuture.completedFuture(true);
        return currency == null
                ? economy.hasBalance(playerUuid, price)
                : economy.hasBalance(playerUuid, currency, price);
    }

    private CompletableFuture<Boolean> withdraw(EconomyService economy, UUID playerUuid, String currency, double price) {
        if (price <= 0)
            return CompletableFuture.completedFuture(true);
        return currency == null
                ? economy.withdraw(playerUuid, price)
                : economy.withdraw(playerUuid, currency, price);
    }

    /**
     * Gives back the price of a level whose purchase could not be completed after the charge went
     * through. A failed refund is logged loudly — it needs manual reconciliation.
     */
    private CompletableFuture<Void> refund(EconomyService economy, UUID playerUuid, String currency, double price) {
        if (price <= 0)
            return CompletableFuture.completedFuture(null);
        CompletableFuture<Boolean> deposit = currency == null
                ? economy.deposit(playerUuid, price)
                : economy.deposit(playerUuid, currency, price);
        return deposit
                .handle((success, throwable) -> {
                    if (throwable != null || !Boolean.TRUE.equals(success))
                        this.plugin.getSLF4JLogger().error("Failed to refund {} {} to {} after a failed upgrade purchase",
                                price, currency == null ? "(default currency)" : currency, playerUuid, throwable);
                    return null;
                });
    }

    private void runUnlockActions(IslandUpgrade.Level level, Player player) {
        try {
            level.unlockActions().run(player);
        } catch (Exception exception) {
            this.plugin.getSLF4JLogger().error("Failed to run unlock actions for upgrade level {}", level.level(), exception);
        }
    }

    // =====================================================================================
    //  Effects
    // =====================================================================================

    /**
     * The configured effect magnitude of an island's current level of {@code type}, or
     * {@code fallback} when neither that level nor level 0 is configured.
     */
    public double value(Island island, UpgradeType type, double fallback) {
        return value(type, island.upgradeLevel(type), fallback);
    }

    /**
     * The configured effect magnitude of a specific level. When that exact level has no entry — a
     * trimmed blueprint, or a level set past the configured maximum — the highest configured level
     * at or below it is used, so an island keeps the effect it has paid for instead of silently
     * dropping to level 0 (which would, for instance, snap a large border back to its starting size).
     */
    public double value(UpgradeType type, int level, double fallback) {
        IslandUpgrade blueprint = this.blueprints.get(type);
        if (blueprint == null)
            return fallback;

        IslandUpgrade.Level configured = blueprint.levels().get(level);
        if (configured == null)
            configured = blueprint.levels().entrySet().stream()
                    .filter(entry -> entry.getKey() <= level)
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .orElse(null);
        return configured == null ? fallback : configured.value();
    }

    /** The member cap of an island — its {@link UpgradeType#MEMBERS_LIMIT} value. */
    public int memberLimit(Island island) {
        return (int) value(island, UpgradeType.MEMBERS_LIMIT, this.plugin.configuration().defaultMemberLimit());
    }

    /** The coop cap of an island — its {@link UpgradeType#COOP_LIMIT} value. */
    public int coopLimit(Island island) {
        return (int) value(island, UpgradeType.COOP_LIMIT, this.plugin.configuration().defaultCoopLimit());
    }

    /** The hopper cap of an island — its {@link UpgradeType#HOPPERS_LIMIT} value. */
    public int hopperLimit(Island island) {
        return (int) value(island, UpgradeType.HOPPERS_LIMIT, this.plugin.configuration().defaultHopperLimit());
    }

    /** The minecart cap of an island — its {@link UpgradeType#MINECART_LIMITS} value. */
    public int minecartLimit(Island island) {
        return (int) value(island, UpgradeType.MINECART_LIMITS, this.plugin.configuration().defaultMinecartLimit());
    }

    /**
     * How much faster than vanilla this island's crops advance a growth stage — its
     * {@link UpgradeType#CROP_GROWTH_SPEED} value, never below {@code 1}.
     */
    public double cropGrowthMultiplier(Island island) {
        return multiplier(island, UpgradeType.CROP_GROWTH_SPEED, this.plugin.configuration().defaultCropGrowthMultiplier());
    }

    /**
     * How much faster than vanilla this island's spawners re-arm — its
     * {@link UpgradeType#SPAWNERS_RATE} value, never below {@code 1}.
     */
    public double spawnerRateMultiplier(Island island) {
        return multiplier(island, UpgradeType.SPAWNERS_RATE, this.plugin.configuration().defaultSpawnerRateMultiplier());
    }

    /**
     * How many times the vanilla amount mobs drop when they die on this island — its
     * {@link UpgradeType#MOB_DROPS} value, never below {@code 1}.
     */
    public double mobDropsMultiplier(Island island) {
        return multiplier(island, UpgradeType.MOB_DROPS, this.plugin.configuration().defaultMobDropsMultiplier());
    }

    /**
     * A multiplier-shaped upgrade value, floored at {@code 1}. A blueprint that configures a level
     * below 1 would make the island worse than vanilla the moment it was bought, which is never what
     * an upgrade is for — such a level is treated as no bonus rather than honoured.
     */
    private double multiplier(Island island, UpgradeType type, double fallback) {
        return Math.max(1, value(island, type, fallback));
    }

    /**
     * The generator an island currently uses: the blueprint named by the {@code key} of its
     * {@link UpgradeType#GENERATOR} level, falling back to the configured default generator.
     */
    public GeneratorConfiguration generator(Island island) {
        IslandUpgrade blueprint = this.blueprints.get(UpgradeType.GENERATOR);
        if (blueprint != null) {
            IslandUpgrade.Level level = blueprint.levels().get(island.upgradeLevel(UpgradeType.GENERATOR));
            if (level != null && level.key() != null && !level.key().isBlank()) {
                GeneratorConfiguration generator = this.plugin.generators().findById(level.key()).orElse(null);
                if (generator != null)
                    return generator;
                this.plugin.getSLF4JLogger().warn("Generator upgrade level {} references unknown generator '{}'", level.level(), level.key());
            }
        }
        return this.plugin.generators().defaultGenerator();
    }

    /**
     * Applies every world-state upgrade effect to an island hosted on this server. Called when an
     * island world finishes loading and whenever an upgrade level changes (locally or remotely).
     * A no-op when the world is not loaded here. Must run on the main thread.
     */
    public void applyEffects(Island island) {
        applyEffects(island, null);
    }

    /**
     * @param changed the upgrade that changed, or {@code null} to apply all of them
     */
    public void applyEffects(Island island, UpgradeType changed) {
        if (changed == null || changed == UpgradeType.WORLDBORDER_SIZE)
            applyWorldBorder(island);
    }

    /** Applies an island's {@link UpgradeType#WORLDBORDER_SIZE} level to its world, if loaded here. */
    public void applyWorldBorder(Island island) {
        World world = this.plugin.worlds()
                .findByIslandId(island.uniqueId())
                .map(instance -> instance.getBukkitWorld())
                .orElse(null);
        if (world == null)
            return;

        double size = value(island, UpgradeType.WORLDBORDER_SIZE, this.plugin.configuration().defaultWorldBorderSize());
        if (size <= 0)
            return;

        world.getWorldBorder().setCenter(island.spawnX(), island.spawnZ());
        world.getWorldBorder().setSize(size);
    }

    /**
     * Re-applies effects for an island by id, if it is cached and hosted on this server. Used by the
     * repository's cross-server update handler, where only the island id is known.
     */
    public void applyEffects(UUID islandId) {
        this.plugin.islands()
                .repository()
                .findCachedById(islandId)
                .ifPresent(island -> Bukkit.getScheduler().runTask(this.plugin, () -> applyEffects(island)));
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
