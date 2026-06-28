package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.repository.IslandRepository;

import lombok.Getter;

@Getter
public class IslandService {

    private final AstralSkyblock plugin;
    private final IslandRepository repository;

    public IslandService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new IslandRepository(plugin);
    }

    public void create(Player player, IslandBlueprint blueprint) {
        long startTime = System.currentTimeMillis();
        Island island = new Island(
                UUID.randomUUID(),
                player.getName(),
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
                        // TODO: Send message
                        return;
                    } else if (result == null) {
                        this.plugin.getSLF4JLogger().error("Failed to save island for player {}: result is null", player.getName());
                        // TODO: Send message
                        return;
                    }


                    // Create default role
                    this.plugin.roles()
                            .saveDefaults(island.uniqueId())
                            .exceptionally(throwable1 -> {
                                this.plugin.getSLF4JLogger().error("Failed to save default roles for island {}", island.uniqueId(), throwable1);
                                // TODO: Send message
                                return null;
                            })
                            .thenAccept(roles -> {
                                // Add owner
                                this.plugin.members()
                                        .addOwner(island.uniqueId(), player.getUniqueId())
                                        .exceptionally(throwable1 -> {
                                            this.plugin.getSLF4JLogger().error("Failed to add owner for island {}", island.uniqueId(), throwable1);
                                            // TODO: Send message
                                            return null;
                                        })
                                        .thenAccept(member -> {
                                            if (member == null) {
                                                this.plugin.getSLF4JLogger().error("Failed to add owner for island {}: result is null", island.uniqueId());
                                                // TODO: Send message
                                                return;
                                            }

                                            // Create world
                                            this.plugin.worlds()
                                                    .create(island.uniqueId(), blueprint)
                                                    .whenComplete((worldInstance, throwable1) -> {
                                                        if (throwable1 != null) {
                                                            this.plugin.getSLF4JLogger().error("Failed to create island for player {}", player.getName(), throwable1);
                                                            // TODO: Send message
                                                            return;
                                                        } else if (worldInstance == null) {
                                                            this.plugin.getSLF4JLogger().error("Failed to create island for player {}: result is null", player.getName());
                                                            // TODO: Send message
                                                            return;
                                                        }
                                                        player.teleportAsync(worldInstance.getBukkitWorld().getSpawnLocation());

                                                        this.plugin.getSLF4JLogger().info("Island created for player {} in {} ms", island.uniqueId(), System.currentTimeMillis() - startTime);
                                                        // TODO: Send message
                                                    });
                                        });
                            });
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
                        return;
                    }

                    // Delete world
                    this.plugin.worlds().delete(island.uniqueId());

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
