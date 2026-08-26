package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.ChatService;
import com.astralrealms.core.service.impl.TeleportationService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.member.IslandMemberLeaveEvent;
import com.astralrealms.skyblock.messaging.packet.island.IslandEvictPacket;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.repository.BanRepository;
import com.astralrealms.skyblock.utils.ASConstants;

/**
 * Island bans: who may not set foot on an island.
 *
 * <p>A ban is authoritative for entry — {@link com.astralrealms.skyblock.listener.IslandListener}
 * blocks banned players from teleporting in, {@link WarpService} and {@link IslandService} refuse to
 * route them, and {@link InvitationService} refuses to invite them. Banning also severs any existing
 * tie: a banned member loses their membership and a banned coop loses their grant, and a banned
 * player standing on the island is evicted to the configured fallback group — on whichever server
 * currently hosts the island, via {@link IslandEvictPacket}.
 */
public class BanService {

    private final AstralSkyblock plugin;
    private final BanRepository repository;

    public BanService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new BanRepository(plugin);
        plugin.messaging().registerExchange(ASConstants.BAN_SYNC_CHANNEL, packet -> {
            if (packet instanceof IslandEvictPacket evict)
                handleEvictPacket(evict);
        });
    }

    public BanRepository repository() {
        return this.repository;
    }

    // =========================================================================
    //  Write operations
    // =========================================================================

    /**
     * Bans a player from an island. The executor must hold {@link IslandPermission#BAN_MEMBER} and,
     * when the target is a member, must outrank them (the owner can never be banned). Strips the
     * target's membership or coop grant, evicts them from the island world, and notifies both sides.
     */
    public CompletableFuture<Void> ban(Island island, Player executor, UUID targetUuid, @Nullable String reason) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(executor)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(executor, IslandPermission.BAN_MEMBER)) {
            ASMessages.NO_PERMISSION.message(executor);
            return CompletableFuture.completedFuture(null);
        }
        if (executor.getUniqueId().equals(targetUuid)) {
            ASMessages.CANNOT_BAN_SELF.message(executor, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (isBanned(island.uniqueId(), targetUuid)) {
            ASMessages.PLAYER_ALREADY_BANNED.message(executor, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        IslandMember target = island.findMember(targetUuid).orElse(null);
        if (target != null && !canBanMember(island, executor, target)) {
            ASMessages.MEMBER_HIGHER_ROLE.message(executor, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        boolean wasMember = target != null;
        // The ban is written first: if severing their membership afterwards fails, the player is at
        // least barred from coming back. The reverse order could kick them without banning them.
        return repository.ban(new IslandBan(
                        island.uniqueId(), targetUuid, executor.getUniqueId(),
                        reason == null || reason.isBlank() ? null : reason,
                        System.currentTimeMillis()))
                .thenCompose(ban -> severTies(island, targetUuid, wasMember).thenApply(ignored -> ban))
                .thenCompose(ban -> this.plugin.islands()
                        .refreshBans(island.uniqueId())
                        .thenApply(refreshed -> ban))
                .handle((ban, throwable) -> {
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(executor, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to ban {} from island {}", targetUuid, island.uniqueId(), throwable);
                        return null;
                    }

                    placeholders.registerPlaceholder(ban);
                    evict(island.uniqueId(), targetUuid);

                    // Notify executor
                    ASMessages.PLAYER_BANNED_SENDER.message(executor, placeholders);

                    // Notify banned player
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(targetUuid, ASMessages.PLAYER_BANNED_TARGET.component(placeholders));
                    return null;
                });
    }

    /**
     * Lifts a ban. The executor must hold {@link IslandPermission#BAN_MEMBER}. Messages the executor
     * when no such ban exists.
     */
    public CompletableFuture<Void> unban(Island island, Player executor, UUID targetUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(executor)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(executor, IslandPermission.BAN_MEMBER)) {
            ASMessages.NO_PERMISSION.message(executor);
            return CompletableFuture.completedFuture(null);
        }
        if (!isBanned(island.uniqueId(), targetUuid)) {
            ASMessages.BAN_NOT_FOUND.message(executor, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return repository.unban(island.uniqueId(), targetUuid)
                .thenCompose(ignored -> this.plugin.islands().refreshBans(island.uniqueId()))
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        ASMessages.UNEXPECTED_ERROR.message(executor, placeholders);
                        this.plugin.getSLF4JLogger().error("Failed to unban {} from island {}", targetUuid, island.uniqueId(), throwable);
                        return null;
                    }

                    // Notify executor
                    ASMessages.PLAYER_UNBANNED_SENDER.message(executor, placeholders);

                    // Notify unbanned player
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(targetUuid, ASMessages.PLAYER_UNBANNED_TARGET.component(placeholders));
                    return null;
                });
    }

    // =========================================================================
    //  Read operations
    // =========================================================================

    /** O(1) ban check backed by the repository's in-memory secondary index. */
    public boolean isBanned(UUID islandId, UUID playerUuid) {
        return repository.isBanned(islandId, playerUuid);
    }

    /** Every ban of an island; primes the island's slice from the database on first access. */
    public CompletableFuture<List<IslandBan>> findByIsland(UUID islandId) {
        return repository.findByIsland(islandId);
    }

    /** A single ban, read through the cache. */
    public CompletableFuture<Optional<IslandBan>> findBan(UUID islandId, UUID playerUuid) {
        return repository.findBan(islandId, playerUuid);
    }

    // =========================================================================
    //  Eviction
    // =========================================================================

    /**
     * Removes a player from an island world they are standing in. Handled locally when the player is
     * online here and broadcast otherwise, since the island may be hosted on another server.
     */
    public void evict(UUID islandId, UUID playerUuid) {
        if (!evictLocally(islandId, playerUuid))
            this.plugin.messaging()
                    .send(ASConstants.BAN_SYNC_CHANNEL, new IslandEvictPacket(islandId, playerUuid))
                    .exceptionally(throwable -> {
                        this.plugin.getSLF4JLogger().error("Failed to broadcast eviction of {} from island {}", playerUuid, islandId, throwable);
                        return null;
                    });
    }

    /**
     * Sends the player to the configured fallback group if they are online here and standing in the
     * island's world.
     *
     * @return whether the player was found in that world on this server
     */
    private boolean evictLocally(UUID islandId, UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.getWorld().getName().equals(islandId.toString()))
            return false;

        AstralPaperAPI.getService(TeleportationService.class)
                .orElseThrow()
                .sendToGroup(playerUuid, this.plugin.configuration().fallbackGroup())
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().error("Failed to evict {} from island {}", playerUuid, islandId, throwable);
                    return null;
                });
        return true;
    }

    private void handleEvictPacket(IslandEvictPacket packet) {
        evictLocally(packet.islandId(), packet.playerUuid());
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Whether {@code executor} outranks {@code target}. Owners outrank everyone; the island owner
     * can never be banned.
     */
    private boolean canBanMember(Island island, Player executor, IslandMember target) {
        if (target.isOwner())
            return false;
        if (executor.hasPermission("skyblock.admin"))
            return true;

        IslandMember executorMember = island.findMember(executor.getUniqueId()).orElse(null);
        if (executorMember == null || executorMember.isOwner())
            return true; // the owner (or an outsider with the permission) outranks a plain member
        return executorMember.role() == null || target.role() == null
               || executorMember.role().weight() > target.role().weight();
    }

    /**
     * Drops the target's membership or coop grant so the ban leaves no residual access.
     */
    private CompletableFuture<Void> severTies(Island island, UUID targetUuid, boolean isMember) {
        CompletableFuture<Void> membership = isMember
                ? this.plugin.members().removeSilently(island, targetUuid, IslandMemberLeaveEvent.Reason.BANNED)
                : CompletableFuture.completedFuture(null);
        return membership.thenCompose(ignored -> island.findCoop(targetUuid).isPresent()
                ? this.plugin.coops().removeSilently(island, targetUuid)
                : CompletableFuture.completedFuture(null));
    }
}
