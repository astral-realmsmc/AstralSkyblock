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
import com.astralrealms.skyblock.model.role.IslandPermission;
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

        // A banned player may not be invited back in until the ban is lifted.
        if (plugin.bans().isBanned(island.uniqueId(), recipient.uniqueId())) {
            ASMessages.PLAYER_BANNED_CANNOT_INVITE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        // Upgrade-driven caps: inviting into a full island would only fail on accept.
        if (isFull(island, type, placeholders)) {
            (type == InvitationType.MEMBER ? ASMessages.MEMBER_LIMIT_REACHED : ASMessages.COOP_LIMIT_REACHED)
                    .message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        IslandInvitation invitation = IslandInvitation.create(
                island.uniqueId(),
                sender.getUniqueId(),
                recipient.uniqueId(),
                type
        );

        // No read-then-insert: the unique key on (island, recipient, type) is what decides whether
        // one is already pending, so concurrent invites cannot both get through.
        return repository.create(invitation)
                            .<Void>handle((created, ex) -> {
                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to create invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return null;
                                }
                                if (!created) {
                                    ASMessages.INVITATION_ALREADY_SENT.message(sender, placeholders);
                                    return null;
                                }

                                // Notify sender
                                ASMessages.INVITATION_SENT.message(sender, placeholders);

                                // Notify recipient
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(recipient.uniqueId(), ASMessages.INVITATION_RECEIVED.component(placeholders));
                                return null;
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

                    // The island may have filled up (or banned the recipient) since the invite was
                    // sent, so the caps are re-checked at the moment they would actually be crossed.
                    PlaceholderContainer checks = AstralPaperAPI.createPlaceholderContainer(player)
                            .registerPlaceholder(island);
                    if (plugin.bans().isBanned(islandId, player.getUniqueId())) {
                        ASMessages.BANNED_FROM_ISLAND.message(player, checks);
                        return CompletableFuture.completedFuture(null);
                    }
                    // An invitation is only as good as its sender's standing: one who has since been
                    // kicked, or lost the right to invite, cannot still let people in.
                    if (!senderMayStillInvite(island, invitation)) {
                        ASMessages.INVITATION_NOT_FOUND.message(player);
                        return repository.delete(invitation.uniqueId()).thenApply(ignored -> null);
                    }
                    if (invitation.type() == InvitationType.MEMBER
                        && plugin.members().findPlayerIsland(player.getUniqueId()).isPresent()) {
                        ASMessages.ALREADY_HAS_ISLAND.message(player, checks);
                        return CompletableFuture.completedFuture(null);
                    }
                    if (invitation.type() == InvitationType.COOP
                        && (island.findMember(player.getUniqueId()).isPresent() || island.findCoop(player.getUniqueId()).isPresent())) {
                        ASMessages.PLAYER_ALREADY_COOP.message(player, checks.registerDirect("target", new MinecraftPlayerPlaceholder(player.getUniqueId())));
                        return repository.delete(invitation.uniqueId()).thenApply(ignored -> null);
                    }
                    if (isFull(island, invitation.type(), checks)) {
                        (invitation.type() == InvitationType.MEMBER
                                ? ASMessages.MEMBER_LIMIT_REACHED
                                : ASMessages.COOP_LIMIT_REACHED).message(player, checks);
                        return CompletableFuture.completedFuture(null);
                    }

                    // Claimed (deleted) before acting on it: of two accepts racing — a double click,
                    // two servers — only the one that removes the row goes on to join.
                    CompletableFuture<?> action = repository.claim(invitation.uniqueId())
                            .thenCompose(claimed -> {
                                if (!claimed)
                                    return CompletableFuture.<Object>failedFuture(new InvitationGoneException());
                                if (invitation.type() == InvitationType.MEMBER)
                                    return members.addMember(island, player.getUniqueId(), invitation.senderId()).thenApply(Object.class::cast);
                                return coops.add(island, invitation.senderId(), player.getUniqueId()).thenApply(Object.class::cast);
                            });
                    return action
                            .<Void>handle((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                                        .registerPlaceholder(island)
                                        .registerDirect("sender", new MinecraftPlayerPlaceholder(invitation.senderId()));

                                if (ex != null && (ex instanceof InvitationGoneException || ex.getCause() instanceof InvitationGoneException)) {
                                    ASMessages.INVITATION_NOT_FOUND.message(player);
                                    return null;
                                }
                                if (ex != null) {
                                    // The island filled up between the check above and the write —
                                    // another server got there first. That is an ordinary outcome,
                                    // not a failure to report as one.
                                    IslandFullException full = asIslandFull(ex);
                                    if (full != null) {
                                        (full.member() ? ASMessages.MEMBER_LIMIT_REACHED : ASMessages.COOP_LIMIT_REACHED)
                                                .message(player, placeholders.registerDirect("limit", full.limit()));
                                        return null;
                                    }

                                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to accept invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return null;
                                }

                                // Notify recipient
                                ASMessages.INVITATION_ACCEPTED_RECIPIENT.message(player, placeholders);

                                // Notify sender
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(invitation.senderId(), ASMessages.INVITATION_ACCEPTED_SENDER.component(placeholders));
                                return null;
                            });
                })
                // Only failures the step above never saw land here: the pending-invitation lookup
                // itself, or a check that threw before the write was chained.
                .exceptionally(throwable -> {
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    plugin.getSLF4JLogger().error("Failed to accept an invitation for {}", player.getName(), throwable);
                    return null;
                });
    }

    /**
     * Declines the pending invitation from {@code islandId} for {@code recipientId}.
     * Silently returns if no pending invitation exists.
     */
    public CompletableFuture<Void> decline(Player player, UUID islandId) {
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

                    return repository.delete(opt.get().uniqueId())
                            .<Void>handle((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                                        .registerPlaceholder(island)
                                        .registerDirect("sender", new MinecraftPlayerPlaceholder(opt.get().senderId()));

                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to decline invitation for island {}: {}", islandId, ex.getMessage(), ex);
                                    return null;
                                }

                                // Notify recipient
                                ASMessages.INVITATION_DECLINED_RECIPIENT.message(player, placeholders);

                                // Notify sender
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(opt.get().senderId(), ASMessages.INVITATION_DECLINED_SENDER.component(placeholders));
                                return null;
                            });
                })
                // Only failures the step above never saw land here: the pending-invitation lookup
                // itself, or a check that threw before the write was chained.
                .exceptionally(throwable -> {
                    ASMessages.UNEXPECTED_ERROR.message(player);
                    plugin.getSLF4JLogger().error("Failed to decline an invitation for {}", player.getName(), throwable);
                    return null;
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
                    // Not only the sender: one who left or was kicked can no longer cancel, and the
                    // island's owner and inviters must be able to withdraw what was sent in its name.
                    if (opt.isEmpty() || !mayCancel(island, sender, opt.get())) {
                        ASMessages.INVITATION_NOT_FOUND.message(sender);
                        return CompletableFuture.completedFuture(null);
                    }

                    return repository.delete(opt.get().uniqueId())
                            .<Void>handle((ignored, ex) -> {
                                PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender)
                                        .registerPlaceholder(island)
                                        .registerDirect("target", new MinecraftPlayerPlaceholder(target));

                                if (ex != null) {
                                    ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                                    plugin.getSLF4JLogger().error("Failed to cancel invitation for island {}: {}", island.uniqueId(), ex.getMessage(), ex);
                                    return null;
                                }

                                // Notify sender
                                ASMessages.INVITATION_CANCELLED_SENDER.message(sender, placeholders);

                                // Notify recipient
                                AstralPaperAPI.getService(ChatService.class)
                                        .orElseThrow()
                                        .sendMessage(target.uniqueId(), ASMessages.INVITATION_CANCELLED_RECIPIENT.component(placeholders));
                                return null;
                            });
                })
                // Only failures the step above never saw land here: the pending-invitation lookup
                // itself, or a check that threw before the write was chained.
                .exceptionally(throwable -> {
                    ASMessages.UNEXPECTED_ERROR.message(sender);
                    plugin.getSLF4JLogger().error("Failed to cancel an invitation for {}", sender.getName(), throwable);
                    return null;
                });
    }

    /** Another accept claimed the invitation first. */
    private static final class InvitationGoneException extends IllegalStateException {
    }

    private static boolean mayCancel(Island island, Player player, IslandInvitation invitation) {
        if (invitation.senderId().equals(player.getUniqueId()) || island.isOwnerOrStaff(player))
            return true;
        return island.hasPermission(player, invitation.type() == InvitationType.MEMBER
                ? IslandPermission.INVITE_MEMBER
                : IslandPermission.COOP_MEMBER);
    }

    /** Whether the invitation's sender is still a member of the island allowed to send it. */
    private static boolean senderMayStillInvite(Island island, IslandInvitation invitation) {
        IslandPermission required = invitation.type() == InvitationType.MEMBER
                ? IslandPermission.INVITE_MEMBER
                : IslandPermission.COOP_MEMBER;
        return island.findMember(invitation.senderId())
                .map(sender -> sender.isOwner() || (sender.role() != null && sender.role().hasPermission(required)))
                .orElse(false);
    }

    /** Unwraps the completion wrapper a failed future arrives in, if it holds a full-island refusal. */
    private static IslandFullException asIslandFull(Throwable throwable) {
        if (throwable instanceof IslandFullException full)
            return full;
        return throwable.getCause() instanceof IslandFullException cause ? cause : null;
    }

    /**
     * Whether the island has no room left for another member (or coop) under its upgrade-driven cap.
     * Registers the applicable limit as the {@code limit} placeholder for the caller's message.
     *
     * <p>An early, friendly check only: the cap is enforced for real by {@link MemberService} and
     * {@link CoopService} at the moment the row is written.
     */
    private boolean isFull(Island island, InvitationType type, PlaceholderContainer placeholders) {
        int limit = type == InvitationType.MEMBER
                ? plugin.upgrades().memberLimit(island)
                : plugin.upgrades().coopLimit(island);
        int current = type == InvitationType.MEMBER ? island.members().size() : island.coops().size();
        placeholders.registerDirect("limit", limit);
        return current >= limit;
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
        pruneExpired().exceptionally(throwable -> {
            plugin.getSLF4JLogger().warn("Failed to prune expired invitations", throwable);
            return null;
        });
    }
}
