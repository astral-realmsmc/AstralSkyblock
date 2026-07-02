package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.astralrealms.core.model.player.MinecraftPlayer;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.ChatService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.InvitationType;
import com.astralrealms.skyblock.model.member.IslandInvitation;
import com.astralrealms.skyblock.repository.InvitationRepository;

public class InvitationService {

    private static final long PRUNE_INTERVAL_TICKS = 20L * 60;

    private final AstralSkyblock plugin;
    private final InvitationRepository repository;
    private final MemberService members;
    private final CoopService coops;

    public InvitationService(AstralSkyblock plugin, MemberService members, CoopService coops) {
        this.plugin = plugin;
        this.repository = new InvitationRepository(plugin);
        this.members = members;
        this.coops = coops;
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pruneExpiredSync, PRUNE_INTERVAL_TICKS, PRUNE_INTERVAL_TICKS);
    }

    // =========================================================================
    //  Write operations
    // =========================================================================

    /**
     * Sends an invitation from {@code senderId} to {@code recipientId} for the given island.
     * Silently returns if the recipient is already a full member, already a coop (for COOP
     * invitations), or a non-expired invitation already exists for that recipient.
     *
     * <p>No permission check is performed here — the caller (command handler / GUI action)
     * is responsible for verifying the sender has the right to invite.
     */
    public CompletableFuture<Void> create(Island island, Player sender, MinecraftPlayer recipient, InvitationType type) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(recipient));

        // Recipient already a full member — nothing to do.
        if (island.findMember(recipient.uniqueId()).isPresent()) {
            ASMessages.PLAYER_ALREADY_MEMBER.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        // For COOP invitations, skip if the player is already coop.
        if (type == InvitationType.COOP && island.findCoop(recipient.uniqueId()).isPresent()) {
            ASMessages.PLAYER_ALREADY_COOP.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return repository.findPending(island.uniqueId(), recipient.uniqueId())
                .thenCompose(existing -> {
                    if (existing.isPresent()) {
                        ASMessages.INVITATION_ALREADY_SENT.message(sender, placeholders);
                        return CompletableFuture.completedFuture(null);
                    }

                    IslandInvitation invitation = IslandInvitation.create(
                            island.uniqueId(),
                            sender.getUniqueId(),
                            recipient.uniqueId(),
                            type
                    );

                    return repository.create(invitation)
                            .whenComplete((ignored, ex) -> {
                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to create invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return;
                                }

                                // Notify sender
                                ASMessages.INVITATION_SENT.message(sender, placeholders);

                                // Notify recipient
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(recipient.uniqueId(), ASMessages.INVITATION_RECEIVED.component(placeholders));
                            });
                });
    }

    /**
     * Accepts the pending invitation from {@code islandId} for {@code recipientId}.
     * Delegates to {@link MemberService} or {@link CoopService} based on the invitation type,
     * then deletes the invitation row. Silently returns if no pending invitation exists or the
     * island is not found in the local cache.
     */
    public CompletableFuture<Void> accept(Player player, UUID islandId) {
        Island island = plugin.islands()
                .repository()
                .findCachedById(islandId)
                .orElse(null);
        if (island == null) {
            ASMessages.INVITATION_NOT_FOUND.message(player);
            return CompletableFuture.completedFuture(null);
        }

        return repository.findPending(islandId, player.getUniqueId())
                .thenCompose(opt -> {
                    if (opt.isEmpty()) {
                        ASMessages.INVITATION_NOT_FOUND.message(player);
                        return CompletableFuture.completedFuture(null);
                    }

                    IslandInvitation invitation = opt.get();
                    CompletableFuture<?> action = invitation.type() == InvitationType.MEMBER
                            ? members.addMember(island, player.getUniqueId(), invitation.senderId())
                            : coops.add(island, invitation.senderId(), player.getUniqueId());
                    return action.thenCompose(ignored -> repository.delete(invitation.uniqueId()))
                            .whenComplete((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                                        .registerPlaceholder(island)
                                        .registerDirect("sender", new MinecraftPlayerPlaceholder(invitation.senderId()));

                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to accept invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return;
                                }

                                // Notify recipient
                                ASMessages.INVITATION_ACCEPTED_RECIPIENT.message(player, placeholders);

                                // Notify sender
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(invitation.senderId(), ASMessages.INVITATION_ACCEPTED_SENDER.component(placeholders));
                            });
                });
    }

    /**
     * Declines the pending invitation from {@code islandId} for {@code recipientId}.
     * Silently returns if no pending invitation exists.
     */
    public CompletableFuture<Void> decline(Player player, UUID islandId) {
        return repository.findPending(islandId, player.getUniqueId())
                .thenCompose(opt -> {
                    if (opt.isEmpty()) {
                        ASMessages.INVITATION_NOT_FOUND.message(player);
                        return CompletableFuture.completedFuture(null);
                    }

                    return repository.delete(opt.get().uniqueId())
                            .whenComplete((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                                        .registerDirect("sender", new MinecraftPlayerPlaceholder(opt.get().senderId()));

                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to decline invitation for island {}: {}", islandId, ex.getMessage(), ex);
                                    return;
                                }

                                // Notify recipient
                                ASMessages.INVITATION_DECLINED_RECIPIENT.message(player, placeholders);

                                // Notify sender
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(opt.get().senderId(), ASMessages.INVITATION_DECLINED_SENDER.component(placeholders));
                            });
                });
    }

    /**
     * Cancels an outgoing invitation. Only the original sender ({@code senderId}) may cancel.
     * Silently returns if no pending invitation exists for {@code targetId} on this island, or
     * if the sender does not match the invitation's sender.
     */
    public CompletableFuture<Void> cancel(Island island, Player sender, MinecraftPlayer target) {
        return repository.findPending(island.uniqueId(), target.uniqueId())
                .thenCompose(opt -> {
                    if (opt.isEmpty() || !opt.get().senderId().equals(sender.getUniqueId())) {
                        ASMessages.INVITATION_NOT_FOUND.message(sender);
                        return CompletableFuture.completedFuture(null);
                    }

                    return repository.delete(opt.get().uniqueId())
                            .whenComplete((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender)
                                        .registerPlaceholder(island)
                                        .registerDirect("target", new MinecraftPlayerPlaceholder(target));

                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to cancel invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return;
                                }

                                // Notify sender
                                ASMessages.INVITATION_CANCELLED_SENDER.message(sender, placeholders);

                                // Notify recipient
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(target.uniqueId(), ASMessages.INVITATION_CANCELLED_RECIPIENT.component(placeholders));
                            });
                });
    }

    /**
     * Bulk-deletes all expired invitations. Delegates to the repository.
     */
    public CompletableFuture<Void> pruneExpired() {
        return repository.pruneExpired();
    }

    // =========================================================================
    //  Read operations
    // =========================================================================

    /**
     * All invitations addressed to a specific player (across all islands), including expired ones.
     * Useful for displaying a player's pending invite list.
     */
    public CompletableFuture<List<IslandInvitation>> findByRecipient(UUID recipientId) {
        return repository.findByRecipient(recipientId);
    }

    /**
     * The first non-expired invitation from {@code islandId} to {@code recipientId}, if any.
     */
    public CompletableFuture<Optional<IslandInvitation>> findPending(UUID islandId, UUID recipientId) {
        return repository.findPending(islandId, recipientId);
    }

    // =========================================================================
    //  Scheduler helpers
    // =========================================================================

    /**
     * Fire-and-forget wrapper called by the Bukkit scheduler every minute.
     * The returned future is intentionally discarded — pruning is best-effort cleanup.
     */
    private void pruneExpiredSync() {
        pruneExpired();
    }
}
