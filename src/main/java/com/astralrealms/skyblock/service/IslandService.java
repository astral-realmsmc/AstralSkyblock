package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.location.NetworkLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadRequestPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadResponsePacket;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
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
            }
            return null;
        });
    }

    public CompletableFuture<NetworkLocation> spawnIsland(Island island) {
        return this.plugin.servers()
                .findHostServer(island.uniqueId())
                .thenCompose((hostServer) -> {
                    if (hostServer == null) {
                        return this.plugin.servers()
                                .findEmptiestServer()
                                .thenCompose((islandServer) -> {
                                    if (islandServer == null) {
                                        this.plugin.getSLF4JLogger().error("Failed to find emptiest server for island {}: result is null", island.uniqueId());
                                        return CompletableFuture.completedFuture(null);
                                    } else if (islandServer.uniqueId().equals(AstralPaperAPI.serverInformation().uniqueId())) {
                                        this.plugin.getSLF4JLogger().info("Found emptiest server {} for island {}: it's the current server, loading locally", islandServer.uniqueId(), island.uniqueId());
                                        return this.plugin.worlds()
                                                .load(island)
                                                .thenApply(worldInstance -> {
                                                    if (worldInstance == null) {
                                                        this.plugin.getSLF4JLogger().error("Failed to load island {} on current server: result is null", island.uniqueId());
                                                        return null;
                                                    }

                                                    return new NetworkLocation(
                                                            island.spawnX(),
                                                            island.spawnY(),
                                                            island.spawnZ(),
                                                            island.spawnYaw(),
                                                            island.spawnPitch(),
                                                            island.uniqueId().toString(),
                                                            AstralPaperAPI.serverInformation().uniqueId()
                                                    );
                                                });
                                    }

                                    this.plugin.getSLF4JLogger().info("Found emptiest server {} for island {}", islandServer.uniqueId(), island.uniqueId());
                                    return this.plugin.messaging()
                                            .sendWithReply(ASConstants.ISLAND_MANAGEMENT_CHANNEL, new IslandLoadRequestPacket(island.uniqueId(), islandServer.uniqueId()))
                                            .thenCompose(reply -> {
                                                if (!(reply instanceof IslandLoadResponsePacket responsePacket)
                                                    || !responsePacket.success())
                                                    return CompletableFuture.completedFuture(null);

                                                return CompletableFuture.completedFuture(new NetworkLocation(
                                                        island.spawnX(),
                                                        island.spawnY(),
                                                        island.spawnZ(),
                                                        island.spawnYaw(),
                                                        island.spawnPitch(),
                                                        island.uniqueId().toString(),
                                                        islandServer.uniqueId()
                                                ));
                                            });
                                });
                    }

                    return CompletableFuture.completedFuture(new NetworkLocation(
                            island.spawnX(),
                            island.spawnY(),
                            island.spawnZ(),
                            island.spawnYaw(),
                            island.spawnPitch(),
                            island.uniqueId().toString(),
                            hostServer
                    ));
                });
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
                            blueprint.spawnLocation().x(),
                            blueprint.spawnLocation().y(),
                            blueprint.spawnLocation().z(),
                            blueprint.spawnLocation().yaw(),
                            blueprint.spawnLocation().pitch(),
                            System.currentTimeMillis(),
                            System.currentTimeMillis()
                    );

                    this.repository.save(island)
                            .whenComplete((result, throwable) -> {
                                if (throwable != null) {
                                    this.plugin.getSLF4JLogger().error("Failed to save island for player {}", player.getName(), throwable);
                                    ASMessages.UNEXPECTED_ERROR.message(player);
                                    return;
                                } else if (result == null) {
                                    this.plugin.getSLF4JLogger().error("Failed to save island for player {}: result is null", player.getName());
                                    ASMessages.UNEXPECTED_ERROR.message(player);
                                    return;
                                }


                                // Create default role
                                this.plugin.roles()
                                        .saveDefaults(island.uniqueId())
                                        .exceptionally(throwable1 -> {
                                            this.plugin.getSLF4JLogger().error("Failed to save default roles for island {}", island.uniqueId(), throwable1);
                                            ASMessages.UNEXPECTED_ERROR.message(player);
                                            return null;
                                        })
                                        .thenAccept(roles -> {
                                            // Add owner
                                            this.plugin.members()
                                                    .addOwner(island.uniqueId(), player.getUniqueId())
                                                    .exceptionally(throwable1 -> {
                                                        this.plugin.getSLF4JLogger().error("Failed to add owner for island {}", island.uniqueId(), throwable1);
                                                        ASMessages.UNEXPECTED_ERROR.message(player);
                                                        return null;
                                                    })
                                                    .thenAccept(member -> {
                                                        if (member == null) {
                                                            this.plugin.getSLF4JLogger().error("Failed to add owner for island {}: result is null", island.uniqueId());
                                                            ASMessages.UNEXPECTED_ERROR.message(player);
                                                            return;
                                                        }

                                                        // Create world
                                                        this.plugin.worlds()
                                                                .create(island.uniqueId(), blueprint)
                                                                .whenComplete((worldInstance, throwable1) -> {
                                                                    if (throwable1 != null) {
                                                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}", player.getName(), throwable1);
                                                                        ASMessages.UNEXPECTED_ERROR.message(player);
                                                                        return;
                                                                    } else if (worldInstance == null) {
                                                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}: result is null", player.getName());
                                                                        ASMessages.UNEXPECTED_ERROR.message(player);
                                                                        return;
                                                                    }
                                                                    player.teleportAsync(worldInstance.getBukkitWorld().getSpawnLocation());

                                                                    this.plugin.getSLF4JLogger().info("Island created for player {} in {} ms", island.uniqueId(), System.currentTimeMillis() - startTime);

                                                                    ASMessages.ISLAND_CREATED.message(
                                                                            player,
                                                                            AstralPaperAPI.createPlaceholderContainer(player)
                                                                                    .registerPlaceholder(island)
                                                                    );
                                                                });
                                                    });
                                        });
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

    public void delete(Player player, Island island) {
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

                    ASMessages.ISLAND_DELETED.message(
                            player,
                            AstralPaperAPI.createPlaceholderContainer(player)
                                    .registerPlaceholder(island)
                    );
                    this.plugin.getSLF4JLogger().info("Island {} deleted for player {}", island.uniqueId(), player.getName());
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
