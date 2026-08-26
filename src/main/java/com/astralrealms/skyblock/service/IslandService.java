package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.location.NetworkLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.island.IslandCreateEvent;
import com.astralrealms.skyblock.event.island.IslandDeletedEvent;
import com.astralrealms.skyblock.listener.IslandSettingsListener;
import com.astralrealms.skyblock.messaging.packet.island.IslandDeletePacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadRequestPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadResponsePacket;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.repository.IslandRepository;
import com.astralrealms.skyblock.utils.ASConstants;

import lombok.Getter;

@Getter
public class IslandService {

    private final AstralSkyblock plugin;
    private final IslandRepository repository;

    public IslandService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new IslandRepository(plugin);

        // Warmup cache
        try {
            this.warmup().join();
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to warm up island cache on startup", e);
        }

        // Messaging listener
        this.plugin.messaging().registerExchange(ASConstants.ISLAND_MANAGEMENT_CHANNEL, (packet, envelope) -> {
            if (packet instanceof IslandLoadRequestPacket request) {
                repository.findById(request.islandId())
                        .thenAccept(island -> {
                            if (island == null) {
                                plugin.getSLF4JLogger().error("Failed to find island {} for load request: result is null", request.islandId());
                                plugin.messaging().replyTo(new IslandLoadResponsePacket(false), envelope);
                                return;
                            } else if (plugin.worlds().getLoadedWorlds().containsKey(island.uniqueId())) {
                                plugin.messaging().replyTo(new IslandLoadResponsePacket(true), envelope);
                                return;
                            }

                            plugin.worlds()
                                    .load(island)
                                    .whenComplete((worldInstance, throwable) -> {
                                        if (throwable != null) {
                                            plugin.getSLF4JLogger().error("Failed to load island {} for load request", request.islandId(), throwable);
                                            plugin.messaging().replyTo(new IslandLoadResponsePacket(false), envelope);
                                            return;
                                        } else if (worldInstance == null) {
                                            plugin.getSLF4JLogger().error("Failed to load island {} for load request: result is null", request.islandId());
                                            plugin.messaging().replyTo(new IslandLoadResponsePacket(false), envelope);
                                            return;
                                        }

                                        plugin.getSLF4JLogger().info("Island {} loaded for load request", request.islandId());
                                        plugin.messaging().replyTo(new IslandLoadResponsePacket(true), envelope);
                                    });
                        }).exceptionally(throwable -> {
                            plugin.getSLF4JLogger().error("Failed to find island {} for load request", request.islandId(), throwable);
                            plugin.messaging().replyTo(new IslandLoadResponsePacket(false), envelope);
                            return null;
                        });
            } else if (packet instanceof IslandDeletePacket delete) {
                // Another server deleted this island. If we host its world, drop it WITHOUT saving so it
                // isn't re-persisted after its storage row is removed. Broadcast, so no reply is expected.
                if (plugin.worlds().getLoadedWorlds().containsKey(delete.islandId()))
                    plugin.worlds()
                            .dropDeleted(delete.islandId())
                            .exceptionally(throwable -> {
                                plugin.getSLF4JLogger().error("Failed to unload deleted island {} on host server", delete.islandId(), throwable);
                                return null;
                            });
            }
            return null;
        });
    }

    /**
     * Resolves the network location of an island's spawn, loading the world (here or on the
     * emptiest island server) if nothing hosts it yet.
     */
    public CompletableFuture<NetworkLocation> spawnIsland(Island island) {
        return resolveLocation(island, island.spawnX(), island.spawnY(), island.spawnZ(),
                island.spawnYaw(), island.spawnPitch());
    }

    /**
     * Resolves a network location inside an island's world, ensuring the world is loaded somewhere
     * first: on the server that already hosts it, on this server when it is the emptiest island
     * server, or on that emptiest server via an {@link IslandLoadRequestPacket}. Completes with
     * {@code null} when no server can host the island.
     *
     * <p>Used both for the island spawn and for warps, which differ only in their coordinates.
     */
    public CompletableFuture<NetworkLocation> resolveLocation(Island island, double x, double y, double z,
                                                              float yaw, float pitch) {
        UUID islandId = island.uniqueId();
        return this.plugin.servers()
                .findHostServer(islandId)
                .thenCompose(hostServer -> {
                    if (hostServer != null)
                        return CompletableFuture.completedFuture(
                                new NetworkLocation(x, y, z, yaw, pitch, islandId.toString(), hostServer));

                    return this.plugin.servers()
                            .findEmptiestServer()
                            .thenCompose(islandServer -> {
                                if (islandServer == null) {
                                    this.plugin.getSLF4JLogger().error("Failed to find emptiest server for island {}: result is null", islandId);
                                    return CompletableFuture.completedFuture(null);
                                }

                                if (islandServer.uniqueId().equals(AstralPaperAPI.serverInformation().uniqueId())) {
                                    this.plugin.getSLF4JLogger().info("Found emptiest server {} for island {}: it's the current server, loading locally", islandServer.uniqueId(), islandId);
                                    return this.plugin.worlds()
                                            .load(island)
                                            .thenApply(worldInstance -> {
                                                if (worldInstance == null) {
                                                    this.plugin.getSLF4JLogger().error("Failed to load island {} on current server: result is null", islandId);
                                                    return null;
                                                }
                                                return new NetworkLocation(x, y, z, yaw, pitch, islandId.toString(),
                                                        AstralPaperAPI.serverInformation().uniqueId());
                                            });
                                }

                                this.plugin.getSLF4JLogger().info("Found emptiest server {} for island {}", islandServer.uniqueId(), islandId);
                                return this.plugin.messaging()
                                        .sendWithReply(ASConstants.ISLAND_MANAGEMENT_CHANNEL, new IslandLoadRequestPacket(islandId, islandServer.uniqueId()))
                                        .thenApply(reply -> {
                                            if (!(reply instanceof IslandLoadResponsePacket responsePacket)
                                                || !responsePacket.success())
                                                return null;
                                            return new NetworkLocation(x, y, z, yaw, pitch, islandId.toString(),
                                                    islandServer.uniqueId());
                                        });
                            });
                }).orTimeout(15, TimeUnit.SECONDS);
    }

    public void create(Player player, String name, IslandBlueprint blueprint) {
        String finalName = name == null || name.isBlank() ? player.getName() : name.trim();
        this.repository.existsByName(finalName)
                .thenAccept(exists -> {
                    if (exists) {
                        ASMessages.NAME_ALREADY_TAKEN.message(player, AstralPaperAPI.createPlaceholderContainer(player).registerDirect("name", finalName));
                        return;
                    }

                    long startTime = System.currentTimeMillis();
                    Island island = new Island(
                            UUID.randomUUID(),
                            finalName,
                            true,
                            0,
                            0,
                            blueprint.spawnLocation().x(),
                            blueprint.spawnLocation().y(),
                            blueprint.spawnLocation().z(),
                            blueprint.spawnLocation().yaw(),
                            blueprint.spawnLocation().pitch(),
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                    );

                    // The island, its default roles + seeded permissions, and the owner member are all
                    // persisted in one transaction; only the world (filesystem, not the DB) is created after.
                    this.repository.create(island, this.plugin.roles().defaultRoleSeeds(island.uniqueId()), player.getUniqueId())
                            .thenCompose(saved -> this.plugin.worlds().create(saved.uniqueId(), blueprint))
                            .whenComplete((worldInstance, throwable) -> {
                                if (throwable != null || worldInstance == null) {
                                    if (throwable != null)
                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}", player.getName(), throwable);
                                    else
                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}: world is null", player.getName());

                                    // The island row (and its roles/owner) was already committed; roll it back so a
                                    // failed world creation doesn't leave an island with no world behind.
                                    this.repository.delete(island.uniqueId())
                                            .exceptionally(rollbackError -> {
                                                this.plugin.getSLF4JLogger().error("Failed to roll back island row for {} after creation failure", island.uniqueId(), rollbackError);
                                                return null;
                                            });

                                    ASMessages.UNEXPECTED_ERROR.message(player);
                                    return;
                                }

                                // Teleport player
                                player.teleportAsync(worldInstance.getBukkitWorld().getSpawnLocation());

                                // Notify
                                ASMessages.ISLAND_CREATED.message(
                                        player,
                                        AstralPaperAPI.createPlaceholderContainer(player)
                                                .registerPlaceholder(island)
                                );

                                // Trigger event
                                new IslandCreateEvent(player, island, worldInstance.getBukkitWorld()).callEvent();

                                // Log
                                this.plugin.getSLF4JLogger().info("Island created for player {} in {} ms", island.uniqueId(), System.currentTimeMillis() - startTime);

                            });
                })
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to check for existing island for player {}", finalName, throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    /**
     * Warms the island cache on startup by loading every island into memory in pages of
     * {@link com.astralrealms.skyblock.utils.ASConstants#ISLAND_WARMUP_PAGE_SIZE}. Runs asynchronously
     * off the database executor; failures are logged but do not abort startup (islands missing from the
     * cache are lazily loaded on first access).
     */
    public CompletableFuture<Void> warmup() {
        long startTime = System.currentTimeMillis();
        return this.repository.warmup()
                .whenComplete((_, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to warm up the island cache", throwable);
                        return;
                    }
                    this.plugin.getSLF4JLogger().info("Warmed up {} islands in {} ms",
                            islands().size(), System.currentTimeMillis() - startTime);
                });
    }

    /**
     * Disbands an island: removes its row (cascading every relationship) and deletes its world.
     * Requires {@link IslandPermission#DISBAND_ISLAND} on the island being deleted — the command's
     * context resolver already restricts which island a player can name, but an admin-supplied or
     * GUI-supplied island must be authorised here too.
     */
    public void delete(Player player, Island island) {
        if (!island.hasPermission(player, IslandPermission.DISBAND_ISLAND)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        this.repository.delete(island.uniqueId())
                .whenComplete((_, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to delete island {} for player {}", island.uniqueId(), player.getName(), throwable);
                        ASMessages.UNEXPECTED_ERROR.message(player);
                        return;
                    }

                    // Delete world
                    this.plugin.worlds()
                            .delete(island.uniqueId())
                            .exceptionally(throwable1 -> {
                                this.plugin.getSLF4JLogger().error("Failed to delete world for island {} (orphaned slime world)", island.uniqueId(), throwable1);
                                return null;
                            });

                    // Notify
                    ASMessages.ISLAND_DELETED.message(
                            player,
                            AstralPaperAPI.createPlaceholderContainer(player)
                                    .registerPlaceholder(island)
                    );

                    // Log
                    this.plugin.getSLF4JLogger().info("Island {} deleted for player {}", island.uniqueId(), player.getName());

                    // Trigger event
                    new IslandDeletedEvent(player, island).callEvent();
                });
    }

    /**
     * Re-cascades a cached island's relationships after a membership or role change. Delegates to
     * {@link IslandRepository#refreshRelationships(UUID)}; called from the member/role write paths.
     */
    public CompletableFuture<Void> refreshRelationships(UUID islandId) {
        return this.repository.refreshRelationships(islandId);
    }

    /**
     * Rebuilds a cached island's upgrade-level snapshot after an upgrade change. Delegates to
     * {@link IslandRepository#refreshUpgrades(UUID)}; called from the upgrade write paths.
     */
    public CompletableFuture<Void> refreshUpgrades(UUID islandId) {
        return this.repository.refreshUpgrades(islandId);
    }

    /**
     * Rebuilds a cached island's ban snapshot after a ban changed. Delegates to
     * {@link IslandRepository#refreshBans(UUID)}; called from the ban write paths.
     */
    public CompletableFuture<Void> refreshBans(UUID islandId) {
        return this.repository.refreshBans(islandId);
    }

    /**
     * Rebuilds a cached island's warp snapshot after a warp changed. Delegates to
     * {@link IslandRepository#refreshWarps(UUID)}; called from the warp write paths.
     */
    public CompletableFuture<Void> refreshWarps(UUID islandId) {
        return this.repository.refreshWarps(islandId);
    }

    public void updateSettings(Player player, Island island) {
        if (!island.hasPermission(player, IslandPermission.SET_SETTINGS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        Map<IslandSettings, Boolean> settings = island.flushSettings();
        if (settings.isEmpty())
            return;

        this.repository.updateSettings(island.uniqueId(), settings)
                .whenComplete((result, throwable) -> {
                    PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                            .registerPlaceholder(island);

                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to update settings for island {} for player {}", island.uniqueId(), player.getName(), throwable);
                        ASMessages.SETTINGS_UPDATE_FAILURE.message(player, placeholders);
                        return;
                    } else if (!result) {
                        ASMessages.SETTINGS_UPDATE_FAILURE.message(player, placeholders);
                        return;
                    }

                    ASMessages.SETTINGS_UPDATE_SUCCESS.message(player, placeholders);

                    // Time/weather locks require world state, not event cancels — re-apply if hosted here
                    Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.worlds()
                            .findByIslandId(island.uniqueId())
                            .ifPresent(instance -> IslandSettingsListener.applyEnvironment(island, instance.getBukkitWorld())));
                });
    }

    @Unmodifiable
    public Collection<Island> islands() {
        return this.repository.cache()
                .synchronous()
                .asMap()
                .values();
    }
}
