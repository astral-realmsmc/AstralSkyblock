package com.astralrealms.skyblock.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.model.location.NetworkLocation;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.event.island.IslandLockChangedEvent;
import com.astralrealms.skyblock.event.island.IslandRenamedEvent;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.island.IslandCreateEvent;
import com.astralrealms.skyblock.event.island.IslandDeletedEvent;
import com.astralrealms.skyblock.listener.IslandSettingsListener;
import com.astralrealms.skyblock.messaging.packet.island.IslandClosedPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandDeletePacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadRequestPacket;
import com.astralrealms.skyblock.messaging.packet.island.IslandLoadResponsePacket;
import com.astralrealms.skyblock.messaging.packet.repository.UniqueObjectUpdatePacket;
import com.astralrealms.skyblock.model.IslandBlueprint;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.island.IslandSettings;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.repository.IslandRepository;
import com.astralrealms.skyblock.utils.ASConstants;
import com.astralrealms.skyblock.utils.PlayerText;
import com.astralrealms.skyblock.utils.Notifier;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;

import lombok.Getter;

@Getter
public class IslandService {

    private static final java.time.Duration LOAD_REQUEST_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final AstralSkyblock plugin;
    private final IslandRepository repository;

    public IslandService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new IslandRepository(plugin);

        // Warm the cache in the background: blocking enable on it meant minutes with the server
        // unable to start on a large network. Names come first, in a single query, so every island
        // can be named in a command while the islands themselves are still loading.
        this.repository.loadNames()
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to load island names on startup", throwable);
                    return null;
                })
                .thenCompose(ignored -> this.warmup());

        // Settings changed on another server: reload them, and re-apply the world locks if we host it.
        this.plugin.messaging().registerExchange(ASConstants.FLAG_UPDATE_CHANNEL, packet -> {
            if (packet instanceof UniqueObjectUpdatePacket update)
                this.repository.refreshSettings(update.uniqueId())
                        .thenAccept(island -> {
                            if (island != null)
                                reapplyEnvironment(island);
                        })
                        .exceptionally(throwable -> {
                            this.plugin.getSLF4JLogger().error("Failed to reload the settings of island {}", update.uniqueId(), throwable);
                            return null;
                        });
        });

        // Messaging listener
        this.plugin.messaging().registerExchange(ASConstants.ISLAND_MANAGEMENT_CHANNEL, (packet, envelope) -> {
            if (packet instanceof IslandLoadRequestPacket request) {
                // The exchange is a fanout: every server receives every request. Only the server the
                // requester picked may load the world, or each one loads its own copy and the last
                // save overwrites the others.
                if (!request.serverId().equals(AstralPaperAPI.serverInformation().uniqueId())
                    || !plugin.configuration().isIslandServer())
                    return null;

                repository.findById(request.islandId())
                        .thenCompose(island -> {
                            if (island == null)
                                throw new IllegalStateException("Island not found: " + request.islandId());
                            return loadLocally(island, request.blueprintId());
                        })
                        .whenComplete((worldInstance, throwable) -> {
                            boolean success = throwable == null && worldInstance != null;
                            if (success)
                                plugin.getSLF4JLogger().info("Island {} loaded for load request", request.islandId());
                            else
                                plugin.getSLF4JLogger().error("Failed to load island {} for load request", request.islandId(), throwable);
                            plugin.messaging().replyTo(new IslandLoadResponsePacket(success), envelope);
                        });
            } else if (packet instanceof IslandClosedPacket closed) {
                // The island was closed on another server; send its visitors away if we host it.
                // Broadcast, so no reply is expected.
                Bukkit.getScheduler().runTask(plugin, () -> expelVisitors(closed.islandId()));
            } else if (packet instanceof IslandDeletePacket delete) {
                // Another server deleted this island. Mark it here unconditionally — not only when we
                // currently host it: a load may be in flight, and it would otherwise register a world
                // for an island whose row is already gone. The unload no-ops when nothing is loaded.
                // Broadcast, so no reply is expected.
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
        return host(island, null)
                .thenApply(server -> {
                    if (server == null)
                        return null;
                    // The caller is about to send someone there: keep the host from idle-unloading
                    // the world in the seconds the transfer takes.
                    this.plugin.servers().markArriving(island.uniqueId());
                    return new NetworkLocation(x, y, z, yaw, pitch, island.uniqueId().toString(), server);
                })
                // Room for a load request plus a retry on another server.
                .orTimeout(25, TimeUnit.SECONDS);
    }

    /**
     * Makes sure the island's world is up on exactly one server and completes with that server, or
     * {@code null} when no server can take it. With a {@code blueprint}, the world does not exist yet
     * and is created from it on the chosen server.
     */
    private CompletableFuture<UUID> host(Island island, @Nullable IslandBlueprint blueprint) {
        return host(island, blueprint, 0);
    }

    private CompletableFuture<UUID> host(Island island, @Nullable IslandBlueprint blueprint, int attempt) {
        UUID islandId = island.uniqueId();
        ServerService servers = this.plugin.servers();
        return servers.findHost(islandId)
                .thenCompose(host -> {
                    if (host == null)
                        return place(island, blueprint, attempt);

                    return servers.isAlive(host.server())
                            .thenCompose(alive -> {
                                if (!alive) {
                                    // The host crashed without releasing it; take the island back.
                                    this.plugin.getSLF4JLogger().warn("Island {} was mapped to dead server {}; placing it again", islandId, host.server());
                                    return servers.releaseHost(islandId, host.server())
                                            .thenCompose(ignored -> place(island, blueprint, attempt));
                                }

                                // Still loading there, or mapped to us: ask for the load. It coalesces
                                // with the one in flight and only answers once the world is up, so
                                // nobody is sent to a world that is not there yet.
                                if (host.loading() || host.server().equals(localServer()))
                                    return loadOn(island, host.server(), null);
                                return CompletableFuture.completedFuture(host.server());
                            });
                });
    }

    /** Picks the emptiest island server, claims the island for it, and loads (or creates) it there. */
    private CompletableFuture<UUID> place(Island island, @Nullable IslandBlueprint blueprint, int attempt) {
        UUID islandId = island.uniqueId();
        ServerService servers = this.plugin.servers();
        return servers.findEmptiestServer()
                .thenCompose(target -> {
                    if (target == null) {
                        this.plugin.getSLF4JLogger().error("No island server can host island {}", islandId);
                        return CompletableFuture.completedFuture(null);
                    }

                    UUID targetId = target.uniqueId();
                    return servers.claimHost(islandId, targetId)
                            .thenCompose(claimed -> {
                                if (!claimed)
                                    // Someone placed it first: go wherever they put it.
                                    return attempt < 2
                                            ? host(island, blueprint, attempt + 1)
                                            : CompletableFuture.completedFuture(null);

                                this.plugin.getSLF4JLogger().info("Placing island {} on server {}", islandId, targetId);
                                return loadOn(island, targetId, blueprint)
                                        .handle((server, throwable) -> {
                                            if (server != null)
                                                return CompletableFuture.completedFuture(server);
                                            if (throwable != null)
                                                this.plugin.getSLF4JLogger().error("Failed to place island {} on server {}", islandId, targetId, throwable);
                                            // Free the slot unless the world did come up after all, and try
                                            // another server: this one may be full (its heartbeat is a few
                                            // seconds old) or failing.
                                            return servers.releaseLoadingClaim(islandId, targetId)
                                                    .thenCompose(ignored -> attempt < 2
                                                            ? place(island, blueprint, attempt + 1)
                                                            : CompletableFuture.<UUID>completedFuture(null));
                                        })
                                        .thenCompose(future -> future);
                            });
                });
    }

    /** Loads (or creates) the island on {@code server}; completes with that server, or {@code null}. */
    private CompletableFuture<UUID> loadOn(Island island, UUID server, @Nullable IslandBlueprint blueprint) {
        if (server.equals(localServer()))
            return loadLocally(island, blueprint == null ? null : blueprint.id())
                    .thenApply(instance -> instance == null ? null : server);

        return this.plugin.messaging()
                // Reading a world from MySQL and loading it on a busy server can outlast the 5 s
                // default; the target would then finish a load nobody waits for anymore.
                .sendWithReply(ASConstants.ISLAND_MANAGEMENT_CHANNEL,
                        new IslandLoadRequestPacket(island.uniqueId(), server, blueprint == null ? null : blueprint.id()),
                        LOAD_REQUEST_TIMEOUT)
                .thenApply(reply -> reply instanceof IslandLoadResponsePacket response && response.success()
                        ? server
                        : null);
    }

    private CompletableFuture<SlimeWorldInstance> loadLocally(Island island, @Nullable String blueprintId) {
        // Placement picks servers from heartbeats up to 15 s old, so many requests can land on the
        // same one at once. Past its cap it refuses, and the requester tries elsewhere.
        Map<UUID, SlimeWorldInstance> loaded = this.plugin.worlds().getLoadedWorlds();
        if (!loaded.containsKey(island.uniqueId()) && loaded.size() >= this.plugin.configuration().maximumIslands())
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "This server already hosts its maximum of " + this.plugin.configuration().maximumIslands() + " islands"));

        if (blueprintId == null)
            return this.plugin.worlds().load(island);

        IslandBlueprint blueprint = this.plugin.blueprints().findById(blueprintId).orElse(null);
        if (blueprint == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Unknown blueprint " + blueprintId + " on this server"));
        return this.plugin.worlds().create(island.uniqueId(), blueprint);
    }

    /**
     * Whether {@code name} can name an island: not blank, short enough, and a single word — a name
     * with spaces could never be typed as one command argument ({@code /is go <island>}).
     */
    static boolean isValidIslandName(String name) {
        return name != null && !name.isBlank()
               && PlayerText.withinLimit(name, PlayerText.ISLAND_NAME_LIMIT)
               && name.strip().chars().noneMatch(Character::isWhitespace);
    }

    private static UUID localServer() {
        return AstralPaperAPI.serverInformation().uniqueId();
    }

    public void create(Player player, String name, IslandBlueprint blueprint) {
        if (blueprint == null) {
            this.plugin.getSLF4JLogger().error("{} tried to create an island but no blueprint is available (is a default blueprint configured?)", player.getName());
            ASMessages.UNEXPECTED_ERROR.message(player);
            return;
        }

        // A player belongs to one island at most; the database would refuse the owner row anyway,
        // but only after a transaction, with an unexpected-error message.
        if (this.plugin.members().findPlayerIsland(player.getUniqueId()).isPresent()) {
            ASMessages.ALREADY_HAS_ISLAND.message(player);
            return;
        }

        // A chosen name goes through the same checks as a rename: it is shown to everyone (the
        // leaderboard, messages), so formatting is escaped, and it must fit the column.
        boolean chosen = name != null && !name.isBlank();
        String finalName;
        if (chosen) {
            String sanitised = isValidIslandName(name) ? PlayerText.sanitise(name) : null;
            if (sanitised == null) {
                ASMessages.ISLAND_NAME_INVALID.message(player, AstralPaperAPI.createPlaceholderContainer(player)
                        .registerDirect("maximum", PlayerText.ISLAND_NAME_LIMIT));
                return;
            }
            finalName = sanitised;
        } else
            finalName = player.getName();

        this.repository.existsByName(finalName)
                .thenAccept(exists -> {
                    if (exists && chosen) {
                        ASMessages.NAME_ALREADY_TAKEN.message(player, AstralPaperAPI.createPlaceholderContainer(player).registerDirect("name", finalName));
                        return;
                    }
                    // The default name (the player's) is taken by someone else's island: create it
                    // unnamed rather than refuse — the creation menu offers no way to pick another.
                    String islandName = exists ? null : finalName;

                    long startTime = System.currentTimeMillis();
                    Island island = new Island(
                            UUID.randomUUID(),
                            islandName,
                            false, // open to visitors until the owner closes it with /is close
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
                            // The world is created on an island server picked like any other placement,
                            // never just wherever the command was typed (a hub has no protection and
                            // never unloads it).
                            .thenCompose(saved -> host(saved, blueprint))
                            .whenComplete((server, throwable) -> {
                                if (throwable != null || server == null) {
                                    if (throwable != null)
                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}", player.getName(), throwable);
                                    else
                                        this.plugin.getSLF4JLogger().error("Failed to create island for player {}: no island server could host it", player.getName());

                                    // The island row (and its roles/owner) was already committed; roll it back, and
                                    // drop any world a slow remote creation may still have produced.
                                    this.repository.delete(island.uniqueId())
                                            .thenCompose(ignored -> this.plugin.worlds().delete(island.uniqueId()))
                                            .exceptionally(rollbackError -> {
                                                this.plugin.getSLF4JLogger().error("Failed to roll back island {} after creation failure", island.uniqueId(), rollbackError);
                                                return null;
                                            });

                                    ASMessages.UNEXPECTED_ERROR.message(player);
                                    return;
                                }

                                // Teleport player (possibly to another server)
                                AstralPaperAPI.getService(TeleportationService.class)
                                        .orElseThrow(() -> new IllegalStateException("TeleportationService not found"))
                                        .teleport(player.getUniqueId(), new NetworkLocation(
                                                island.spawnX(), island.spawnY(), island.spawnZ(),
                                                island.spawnYaw(), island.spawnPitch(),
                                                island.uniqueId().toString(), server));

                                // Notify
                                ASMessages.ISLAND_CREATED.message(
                                        player,
                                        AstralPaperAPI.createPlaceholderContainer(player)
                                                .registerPlaceholder(island)
                                );

                                // Trigger event (no world when it was created on another server)
                                new IslandCreateEvent(player, island, Bukkit.getWorld(island.uniqueId().toString())).callEvent();

                                // Log
                                this.plugin.getSLF4JLogger().info("Island {} created for player {} on server {} in {} ms",
                                        island.uniqueId(), player.getName(), server, System.currentTimeMillis() - startTime);
                            });
                })
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to check for existing island for player {}", finalName, throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    return null;
                });
    }

    /**
     * Warms the island cache by loading every island into memory in pages of
     * {@link com.astralrealms.skyblock.utils.ASConstants#ISLAND_WARMUP_PAGE_SIZE}, a few at a time.
     * Runs in the background; failures are logged and skipped (islands missing from the cache are
     * lazily loaded on first access).
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
     * Reserved to the owner (and staff) whatever the roles grant: it cannot be undone, and a role
     * permission can be handed out — even to every visitor — by anyone allowed to edit roles.
     */
    public void delete(Player player, Island island) {
        if (!island.isOwnerOrStaff(player)) {
            ASMessages.NOT_ISLAND_OWNER.message(player);
            return;
        }

        // Taken before the delete: it drops the island's member slice from the cache.
        List<UUID> otherMembers = island.members().stream()
                .map(IslandMember::playerUuid)
                .filter(member -> !member.equals(player.getUniqueId()))
                .toList();

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

                    // Notify, members included, wherever they are on the network
                    PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                            .registerPlaceholder(island);
                    ASMessages.ISLAND_DELETED.message(player, placeholders);
                    otherMembers.forEach(member -> Notifier.send(member, ASMessages.ISLAND_DELETED.component(placeholders)));

                    // Log
                    this.plugin.getSLF4JLogger().info("Island {} deleted for player {}", island.uniqueId(), player.getName());

                    // Trigger event
                    new IslandDeletedEvent(player, island).callEvent();
                });
    }

    // =========================================================================
    //  Identity
    // =========================================================================

    /**
     * Renames an island. Requires {@link IslandPermission#CHANGE_NAME} and a name that is not
     * already taken — {@code islands.name} is unique network-wide, so the check is a database read
     * rather than a cache lookup.
     */
    public void rename(Player player, Island island, String name) {
        if (!island.hasPermission(player, IslandPermission.CHANGE_NAME)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island);

        String sanitised = isValidIslandName(name) ? PlayerText.sanitise(name) : null;
        if (sanitised == null) {
            ASMessages.ISLAND_NAME_INVALID.message(player, placeholders.registerDirect("maximum", PlayerText.ISLAND_NAME_LIMIT));
            return;
        }
        placeholders.registerDirect("name", sanitised);

        if (sanitised.equalsIgnoreCase(island.name())) {
            ASMessages.NAME_ALREADY_TAKEN.message(player, placeholders);
            return;
        }

        this.repository.existsByName(sanitised)
                .thenCompose(exists -> {
                    if (exists) {
                        ASMessages.NAME_ALREADY_TAKEN.message(player, placeholders);
                        return CompletableFuture.completedFuture(null);
                    }

                    String previous = island.name();
                    return this.repository.updateColumns(island.uniqueId(), Map.of("name", sanitised), cached -> cached.name(sanitised))
                            .<Void>handle((ignored, throwable) -> {
                                if (throwable != null) {
                                    // The unique index can still reject the name between the check and
                                    // the write; nothing was applied in memory in that case.
                                    this.plugin.getSLF4JLogger().error("Failed to rename island {} to {}", island.uniqueId(), sanitised, throwable);
                                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                                    return null;
                                }
                                ASMessages.ISLAND_RENAMED.message(player, placeholders);
                                new IslandRenamedEvent(island, player, previous, sanitised).callEvent();
                                return null;
                            });
                })
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to check whether the island name {} is taken", sanitised, throwable);
                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                    return null;
                });
    }

    /**
     * Moves the island spawn to where the player is standing. Requires
     * {@link IslandPermission#SET_HOME} and a position inside the island's own world.
     */
    public void setHome(Player player, Island island) {
        if (!island.hasPermission(player, IslandPermission.SET_HOME)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island);
        if (!player.getWorld().getName().equals(island.uniqueId().toString())) {
            ASMessages.NOT_ON_ISLAND.message(player, placeholders);
            return;
        }

        // Outside the border nobody could use it — and a spawn out there is where visitors land.
        Location location = player.getLocation();
        if (!player.getWorld().getWorldBorder().isInside(location)) {
            ASMessages.NOT_ON_ISLAND.message(player, placeholders);
            return;
        }

        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("spawn_x", location.getX());
        columns.put("spawn_y", location.getY());
        columns.put("spawn_z", location.getZ());
        columns.put("spawn_yaw", location.getYaw());
        columns.put("spawn_pitch", location.getPitch());
        this.repository.updateColumns(island.uniqueId(), columns, cached -> cached.location(location))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to move the spawn of island {}", island.uniqueId(), throwable);
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        return;
                    }
                    ASMessages.ISLAND_HOME_SET.message(player, placeholders);
                });
    }

    // =========================================================================
    //  Access
    // =========================================================================

    /**
     * Opens or closes an island to visitors. Closing requires {@link IslandPermission#CLOSE_ISLAND}
     * and opening {@link IslandPermission#OPEN_ISLAND}; the flag itself is enforced on entry by
     * {@link com.astralrealms.skyblock.listener.IslandListener}.
     */
    public void setLocked(Player player, Island island, boolean locked) {
        IslandPermission required = locked ? IslandPermission.CLOSE_ISLAND : IslandPermission.OPEN_ISLAND;
        if (!island.hasPermission(player, required)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }

        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island);
        if (island.locked() == locked) {
            (locked ? ASMessages.ISLAND_ALREADY_CLOSED : ASMessages.ISLAND_ALREADY_OPEN).message(player, placeholders);
            return;
        }

        this.repository.updateColumns(island.uniqueId(), Map.of("locked", locked), cached -> cached.locked(locked))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to {} island {}", locked ? "close" : "open", island.uniqueId(), throwable);
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        return;
                    }

                    (locked ? ASMessages.ISLAND_CLOSED : ASMessages.ISLAND_OPENED).message(player, placeholders);
                    new IslandLockChangedEvent(island, player, locked).callEvent();

                    // Closing only bars visitors who have yet to arrive; the ones already standing on
                    // the island are sent away by whichever server hosts its world.
                    if (locked)
                        closeToVisitors(island.uniqueId());
                });
    }

    /**
     * Sends every visitor off a closed island: locally when its world is hosted here, and by
     * broadcast otherwise, since the player who closed it need not be on the hosting server.
     */
    private void closeToVisitors(UUID islandId) {
        if (this.plugin.worlds().findByIslandId(islandId).isPresent()) {
            Bukkit.getScheduler().runTask(this.plugin, () -> expelVisitors(islandId));
            return;
        }

        this.plugin.messaging()
                .send(ASConstants.ISLAND_MANAGEMENT_CHANNEL, new IslandClosedPacket(islandId))
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to broadcast the closing of island {}", islandId, throwable);
                    return null;
                });
    }

    /**
     * Evicts everyone standing in the island's world who is neither an insider nor allowed to walk
     * past a closed gate. Main thread only, and a no-op when the world is not hosted here.
     */
    public void expelVisitors(UUID islandId) {
        Island island = this.repository.findCachedById(islandId).orElse(null);
        if (island == null)
            return;

        this.plugin.worlds()
                .findByIslandId(islandId)
                .ifPresent(instance -> {
                    for (Player player : List.copyOf(instance.getBukkitWorld().getPlayers())) {
                        if (mayEnterClosed(island, player))
                            continue;

                        ASMessages.ISLAND_IS_CLOSED.message(
                                player,
                                AstralPaperAPI.createPlaceholderContainer(player).registerPlaceholder(island));
                        this.plugin.bans().evict(islandId, player.getUniqueId());
                    }
                });
    }

    /** Whether a closed island still lets this player in: its own people, and holders of the bypass. */
    public boolean mayEnterClosed(Island island, Player player) {
        return island.isInsider(player) || island.hasPermission(player, IslandPermission.CLOSE_BYPASS);
    }

    /**
     * Expels a visitor from an island world. Requires {@link IslandPermission#EXPEL_PLAYERS}; the
     * island's own people are kicked or uncooped rather than expelled, and a holder of
     * {@link IslandPermission#EXPEL_BYPASS} cannot be expelled at all.
     *
     * <p>Only a player standing in the island's world on this server can be expelled, which is the
     * only case the command is for — the executor is on the island, and so is their target.
     */
    public void expel(Player executor, Island island, UUID targetUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(executor)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(executor, IslandPermission.EXPEL_PLAYERS)) {
            ASMessages.NO_PERMISSION.message(executor);
            return;
        }
        if (executor.getUniqueId().equals(targetUuid)) {
            ASMessages.CANNOT_EXPEL_SELF.message(executor, placeholders);
            return;
        }

        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.getWorld().getName().equals(island.uniqueId().toString())) {
            ASMessages.PLAYER_NOT_ON_ISLAND.message(executor, placeholders);
            return;
        }
        // Checked before insider status so that the owner and staff — who hold every permission —
        // are reported as protected rather than as ordinary members.
        if (island.hasPermission(target, IslandPermission.EXPEL_BYPASS)) {
            ASMessages.CANNOT_EXPEL_BYPASS.message(executor, placeholders);
            return;
        }
        if (island.isInsider(target)) {
            ASMessages.CANNOT_EXPEL_INSIDER.message(executor, placeholders);
            return;
        }

        this.plugin.bans().evict(island.uniqueId(), targetUuid);
        ASMessages.PLAYER_EXPELLED_SENDER.message(executor, placeholders);
        ASMessages.PLAYER_EXPELLED_TARGET.message(target, placeholders);
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
        // Taken first: a refused save discards the edits rather than leaving them pending.
        Map<IslandSettings, Boolean> settings = island.takePendingSettings(player.getUniqueId());

        if (!island.hasPermission(player, IslandPermission.SET_SETTINGS)) {
            ASMessages.NO_PERMISSION.message(player);
            return;
        }
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

                    // Only now that it is stored; then tell the other servers, since the one that
                    // enforces the settings is whichever hosts the world, rarely the one editing them.
                    island.applySettings(settings);
                    this.repository.publishSettingsChange(island.uniqueId());
                    ASMessages.SETTINGS_UPDATE_SUCCESS.message(player, placeholders);

                    reapplyEnvironment(island);
                });
    }

    /** Re-applies the time/weather locks — world state, not event cancels — when this server hosts the island. */
    private void reapplyEnvironment(Island island) {
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.worlds()
                .findByIslandId(island.uniqueId())
                .ifPresent(instance -> IslandSettingsListener.applyEnvironment(island, instance.getBukkitWorld())));
    }

    @Unmodifiable
    public Collection<Island> islands() {
        return this.repository.cache()
                .synchronous()
                .asMap()
                .values();
    }
}
