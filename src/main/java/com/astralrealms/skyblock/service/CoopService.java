package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.ChatService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.coop.IslandCoopAddEvent;
import com.astralrealms.skyblock.event.coop.IslandCoopRemoveEvent;
import com.astralrealms.skyblock.messaging.packet.island.CoopAddPacket;
import com.astralrealms.skyblock.messaging.packet.island.CoopRemovePacket;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandCoop;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.repository.CoopRepository;
import com.astralrealms.skyblock.utils.ASConstants;

public class CoopService {

    private final AstralSkyblock plugin;
    private final CoopRepository repository;

    public CoopService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CoopRepository(plugin);
        plugin.messaging().registerExchange(ASConstants.COOP_SYNC_CHANNEL, packet -> {
            if (packet instanceof CoopAddPacket coopAddPacket)
                handleCoopAddPacket(coopAddPacket);
            else if (packet instanceof CoopRemovePacket coopRemovePacket)
                handleCoopRemovePacket(coopRemovePacket);
        });
    }

    public CoopRepository repository() {
        return this.repository;
    }

    // =========================================================================
    //  Write operations
    // =========================================================================

    /**
     * Adds a player as a coop member of an island. Persists the entry, updates the local island
     * snapshot, fires {@link IslandCoopAddEvent}, and broadcasts a {@link CoopAddPacket} so other
     * servers can keep their local state in sync.
     *
     * <p>No validation is performed here — the caller (InvitationService) is responsible for
     * checking the coop limit and ensuring the player is not already a member or coop.
     *
     * @return the persisted {@link IslandCoop} entry
     */
    public CompletableFuture<IslandCoop> add(Island island, UUID addedBy, UUID playerUuid) {
        IslandCoop coop = new IslandCoop(island.uniqueId(), playerUuid, addedBy, System.currentTimeMillis());
        return repository.add(coop).thenApply(saved -> {
            island.coops().add(saved);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getPluginManager().callEvent(new IslandCoopAddEvent(island, playerUuid, addedBy)));
            plugin.messaging().send(ASConstants.COOP_SYNC_CHANNEL, new CoopAddPacket(island.uniqueId(), playerUuid, addedBy));
            return saved;
        });
    }

    /**
     * Removes a player from the island's coop list. The remover must have
     * {@link IslandPermission#UNCOOP_MEMBER}. Deletes the entry, updates the local island
     * snapshot, fires {@link IslandCoopRemoveEvent}, and broadcasts a {@link CoopRemovePacket}.
     * Messages the remover on any failed check and notifies the removed player cross-server
     * on success.
     */
    public CompletableFuture<Void> remove(Island island, Player remover, UUID playerUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(remover)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(playerUuid));

        if (!island.hasPermission(remover, IslandPermission.UNCOOP_MEMBER)) {
            ASMessages.NO_PERMISSION.message(remover);
            return CompletableFuture.completedFuture(null);
        }
        if (!isCoop(island.uniqueId(), playerUuid)) {
            ASMessages.COOP_NOT_FOUND.message(remover, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return repository.remove(island.uniqueId(), playerUuid)
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        ASMessages.UNEXPECTED_ERROR.message(remover, placeholders);
                        plugin.getSLF4JLogger().error("Failed to remove coop {} from island {}: {}", playerUuid, island.uniqueId(), ex.getMessage(), ex);
                        return;
                    }

                    island.coops().removeIf(c -> c.playerUuid().equals(playerUuid));
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new IslandCoopRemoveEvent(island, playerUuid)));
                    plugin.messaging().send(ASConstants.COOP_SYNC_CHANNEL, new CoopRemovePacket(island.uniqueId(), playerUuid));

                    // Notify remover
                    ASMessages.COOP_REMOVED_SENDER.message(remover, placeholders);

                    // Notify removed co-op player
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(playerUuid, ASMessages.COOP_REMOVED_TARGET.component(placeholders));
                })
                .thenAccept(ignored -> {
                });
    }

    // =========================================================================
    //  Read operations
    // =========================================================================

    /**
     * O(1) membership check backed by the repository's in-memory secondary index.
     */
    public boolean isCoop(UUID islandId, UUID playerUuid) {
        return repository.isCoop(islandId, playerUuid);
    }

    /**
     * All coop members of an island. Delegates to the repository which primes the cache slice from
     * the database on first access.
     */
    public CompletableFuture<List<IslandCoop>> findByIsland(UUID islandId) {
        return repository.findByIsland(islandId);
    }

    // =========================================================================
    //  Cross-server packet handlers
    // =========================================================================

    /**
     * Receives a {@link CoopAddPacket} broadcast by another server. Brings the local repository
     * secondary index up to date so that {@link #isCoop} remains accurate, and appends the entry to
     * the in-memory island snapshot if the island is cached on this server.
     *
     * <p>Does NOT fire an event (already fired on the originating server) and does NOT persist to DB.
     */
    private void handleCoopAddPacket(CoopAddPacket packet) {
        if (plugin.islands() == null) return;
        IslandCoop coop = new IslandCoop(packet.islandId(), packet.playerId(), packet.addedBy(), System.currentTimeMillis());
        repository.addLocally(coop);
        plugin.islands().repository()
                .findCachedById(packet.islandId())
                .ifPresent(island -> island.coops().add(coop));
    }

    /**
     * Receives a {@link CoopRemovePacket} broadcast by another server. Evicts the entry from the
     * local repository cache and removes it from the in-memory island snapshot if the island is
     * cached on this server.
     *
     * <p>Does NOT fire an event and does NOT touch the database.
     */
    private void handleCoopRemovePacket(CoopRemovePacket packet) {
        if (plugin.islands() == null) return;
        repository.invalidateLocally(new IslandPlayerKey(packet.islandId(), packet.playerId()));
        plugin.islands().repository()
                .findCachedById(packet.islandId())
                .ifPresent(island -> island.coops().removeIf(c -> c.playerUuid().equals(packet.playerId())));
    }
}
