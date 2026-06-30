package com.astralrealms.skyblock.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;

import com.astralrealms.skyblock.AstralSkyblock;
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
    public CompletableFuture<Void> create(Island island, UUID senderId, UUID recipientId, InvitationType type) {
        // Recipient already a full member — nothing to do.
        if (island.findMember(recipientId).isPresent())
            return CompletableFuture.completedFuture(null);
        // For COOP invitations, skip if the player is already coop.
        if (type == InvitationType.COOP && island.findCoop(recipientId).isPresent())
            return CompletableFuture.completedFuture(null);
        return repository.findPending(island.uniqueId(), recipientId)
                .thenCompose(existing -> {
                    if (existing.isPresent())
                        return CompletableFuture.completedFuture(null);
                    IslandInvitation invitation = IslandInvitation.create(
                            island.uniqueId(), senderId, recipientId, type);
                    // TODO: notify recipient
                    return repository.create(invitation);
                });
    }

    /**
     * Accepts the pending invitation from {@code islandId} for {@code recipientId}.
     * Delegates to {@link MemberService} or {@link CoopService} based on the invitation type,
     * then deletes the invitation row. Silently returns if no pending invitation exists or the
     * island is not found in the local cache.
     */
    public CompletableFuture<Void> accept(UUID islandId, UUID recipientId) {
        Island island = plugin.islands().repository().findCachedById(islandId).orElse(null);
        if (island == null)
            return CompletableFuture.completedFuture(null);
        return repository.findPending(islandId, recipientId).thenCompose(opt -> {
            if (opt.isEmpty())
                return CompletableFuture.completedFuture(null);
            IslandInvitation invitation = opt.get();
            CompletableFuture<?> action = invitation.type() == InvitationType.MEMBER
                    ? members.addMember(island, recipientId, invitation.senderId())
                    : coops.add(island, invitation.senderId(), recipientId);
            return action.thenCompose(ignored -> repository.delete(invitation.uniqueId()));
        });
    }

    /**
     * Declines the pending invitation from {@code islandId} for {@code recipientId}.
     * Silently returns if no pending invitation exists.
     */
    public CompletableFuture<Void> decline(UUID islandId, UUID recipientId) {
        return repository.findPending(islandId, recipientId).thenCompose(opt -> {
            if (opt.isEmpty())
                return CompletableFuture.completedFuture(null);
            return repository.delete(opt.get().uniqueId());
        });
    }

    /**
     * Cancels an outgoing invitation. Only the original sender ({@code senderId}) may cancel.
     * Silently returns if no pending invitation exists for {@code targetId} on this island, or
     * if the sender does not match the invitation's sender.
     */
    public CompletableFuture<Void> cancel(Island island, UUID senderId, UUID targetId) {
        return repository.findPending(island.uniqueId(), targetId).thenCompose(opt -> {
            if (opt.isEmpty() || !opt.get().senderId().equals(senderId))
                return CompletableFuture.completedFuture(null);
            return repository.delete(opt.get().uniqueId());
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
