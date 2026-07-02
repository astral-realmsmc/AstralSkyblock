package com.astralrealms.skyblock.service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.core.paper.placeholder.MinecraftPlayerPlaceholder;
import com.astralrealms.core.placeholder.container.PlaceholderContainer;
import com.astralrealms.core.service.impl.ChatService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.configuration.ASMessages;
import com.astralrealms.skyblock.event.member.IslandMemberJoinEvent;
import com.astralrealms.skyblock.event.member.IslandMemberLeaveEvent;
import com.astralrealms.skyblock.messaging.packet.island.MemberJoinPacket;
import com.astralrealms.skyblock.messaging.packet.island.MemberLeavePacket;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.model.role.IslandPermission;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.repository.MemberRepository;
import com.astralrealms.skyblock.utils.ASConstants;

public class MemberService {

    private final AstralSkyblock plugin;
    private final MemberRepository repository;

    public MemberService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new MemberRepository(plugin);
        plugin.messaging().registerExchange(ASConstants.MEMBER_SYNC_CHANNEL, packet -> {
            if (packet instanceof MemberJoinPacket joinPacket)
                handleMemberJoinPacket(joinPacket);
            else if (packet instanceof MemberLeavePacket leavePacket)
                handleMemberLeavePacket(leavePacket);
        });
    }

    // =========================================================================
    //  Read operations
    // =========================================================================

    /**
     * Every member of an island. Primes (and refreshes) the per-island member slice.
     */
    public CompletableFuture<List<IslandMember>> findByIsland(UUID islandId) {
        return this.repository.findByIsland(islandId);
    }

    public Optional<Island> findPlayerIsland(UUID playerId) {
        UUID uniqueId = this.repository.findPlayerIsland(playerId)
                .orElse(null);
        if (uniqueId == null)
            return Optional.empty();
        return this.plugin.islands()
                .repository()
                .findCachedById(uniqueId);
    }

    @Unmodifiable
    public Collection<IslandMember> findIslandMembers(UUID islandId) {
        return this.repository.findIslandMembers(islandId)
                .stream()
                .map(memberId -> this.repository.findCachedById(new MemberKey(islandId, memberId)).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    // =========================================================================
    //  Write operations
    // =========================================================================

    /**
     * Adds a player to an island as a member using the island's default role. Persists the entry,
     * triggers a relationship refresh, fires {@link IslandMemberJoinEvent}, and broadcasts a
     * {@link MemberJoinPacket} so other servers can fire the event locally for player notifications.
     *
     * <p>No validation is performed here — the caller (InvitationService) is responsible for
     * checking the member limit and ensuring the player is not already a member.
     */
    public CompletableFuture<IslandMember> addMember(Island island, UUID playerUuid, UUID invitedBy) {
        IslandRole defaultRole = island.roles().stream()
                .filter(IslandRole::isDefault)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No default role on island: " + island.uniqueId()));
        return repository.add(island.uniqueId(), playerUuid, defaultRole.id())
                .thenApply(member -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(
                                    new IslandMemberJoinEvent(island, playerUuid, invitedBy)));
                    plugin.messaging().send(ASConstants.MEMBER_SYNC_CHANNEL,
                            new MemberJoinPacket(island.uniqueId(), playerUuid, invitedBy));
                    return member;
                });
    }

    /**
     * Kicks a member from the island. The kicker must have {@link IslandPermission#KICK_MEMBER}
     * and outrank the target (owners always outrank everyone). Messages the kicker on any
     * failed check and notifies the kicked player cross-server on success.
     */
    public CompletableFuture<Void> kick(Island island, Player kicker, UUID targetUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(kicker)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(kicker, IslandPermission.KICK_MEMBER)) {
            ASMessages.NO_PERMISSION.message(kicker);
            return CompletableFuture.completedFuture(null);
        }
        IslandMember target = island.findMember(targetUuid).orElse(null);
        if (target == null) {
            ASMessages.MEMBER_NOT_FOUND.message(kicker, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (target.isOwner()) {
            ASMessages.MEMBER_HIGHER_ROLE.message(kicker, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        IslandMember kickerMember = island.findMember(kicker.getUniqueId()).orElse(null);
        // Non-owners must outrank their target; owners (role == null) may kick anyone.
        if (kickerMember != null && !kickerMember.isOwner()
            && kickerMember.role() != null && target.role() != null
            && kickerMember.role().weight() <= target.role().weight()) {
            ASMessages.MEMBER_HIGHER_ROLE.message(kicker, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return repository.remove(island.uniqueId(), targetUuid)
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        ASMessages.UNEXPECTED_ERROR.message(kicker, placeholders);
                        plugin.getSLF4JLogger().error("Failed to kick member {} from island {}: {}", targetUuid, island.uniqueId(), ex.getMessage(), ex);
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                                    island, targetUuid, IslandMemberLeaveEvent.Reason.KICKED)));
                    plugin.messaging().send(ASConstants.MEMBER_SYNC_CHANNEL, new MemberLeavePacket(
                            island.uniqueId(), targetUuid, IslandMemberLeaveEvent.Reason.KICKED));

                    // Notify kicker
                    ASMessages.MEMBER_KICKED_SENDER.message(kicker, placeholders);

                    // Notify kicked player
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(targetUuid, ASMessages.MEMBER_KICKED_TARGET.component(placeholders));
                });
    }

    /**
     * Voluntarily removes the player from their island. Blocked if the player is the island owner.
     * Messages the player on any failed check and confirms the departure on success.
     */
    public CompletableFuture<Void> leave(Island island, Player player) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(player)
                .registerPlaceholder(island);

        IslandMember member = island.findMember(player.getUniqueId()).orElse(null);
        if (member == null) {
            ASMessages.NO_ISLAND.message(player);
            return CompletableFuture.completedFuture(null);
        }
        if (member.isOwner()) {
            ASMessages.OWNER_CANNOT_LEAVE.message(player, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        return repository.remove(island.uniqueId(), player.getUniqueId())
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        ASMessages.UNEXPECTED_ERROR.message(player, placeholders);
                        plugin.getSLF4JLogger().error("Failed to remove member {} from island {}: {}", player.getUniqueId(), island.uniqueId(), ex.getMessage(), ex);
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                                    island, player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY)));
                    plugin.messaging().send(ASConstants.MEMBER_SYNC_CHANNEL, new MemberLeavePacket(
                            island.uniqueId(), player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY));

                    ASMessages.ISLAND_LEFT.message(player, placeholders);
                });
    }

    /**
     * Promotes a member one step up the role ladder. The sender must have
     * {@link IslandPermission#PROMOTE_MEMBERS} and must outrank the resulting role (non-owners cannot
     * promote above themselves). Messages the sender on any failed check and notifies the promoted
     * member cross-server on success.
     */
    public CompletableFuture<Void> promote(Island island, Player sender, UUID targetUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(sender, IslandPermission.PROMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(sender);
            return CompletableFuture.completedFuture(null);
        }
        IslandMember target = island.findMember(targetUuid).orElse(null);
        IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
        if (target == null) {
            ASMessages.MEMBER_NOT_FOUND.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (target.isOwner() || senderMember == null) {
            ASMessages.MEMBER_HIGHER_ROLE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        List<IslandRole> ladder = memberRoleLadder(island);
        int idx = findRoleIndex(ladder, target.role());
        if (idx < 0 || idx >= ladder.size() - 1) {
            ASMessages.MEMBER_ALREADY_HIGHEST_ROLE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        IslandRole next = ladder.get(idx + 1);
        // Non-owners cannot promote to a role at or above their own weight.
        if (!senderMember.isOwner() && senderMember.role() != null
            && next.weight() >= senderMember.role().weight()) {
            ASMessages.MEMBER_PROMOTE_HIGHER.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        placeholders.registerPlaceholder(next);
        return repository.setRole(island.uniqueId(), targetUuid, next.id())
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                        plugin.getSLF4JLogger().error("Failed to promote member {} on island {}: {}", targetUuid, island.uniqueId(), ex.getMessage(), ex);
                        return;
                    }

                    // Notify sender
                    ASMessages.MEMBER_PROMOTED_SENDER.message(sender, placeholders);

                    // Notify promoted member
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(targetUuid, ASMessages.MEMBER_PROMOTED_TARGET.component(placeholders));
                })
                .thenAccept(v -> {
                });
    }

    /**
     * Demotes a member one step down the role ladder. The sender must have
     * {@link IslandPermission#DEMOTE_MEMBERS}. Messages the sender on any failed check and
     * notifies the demoted member cross-server on success.
     */
    public CompletableFuture<Void> demote(Island island, Player sender, UUID targetUuid) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(sender)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(targetUuid));

        if (!island.hasPermission(sender, IslandPermission.DEMOTE_MEMBERS)) {
            ASMessages.NO_PERMISSION.message(sender);
            return CompletableFuture.completedFuture(null);
        }
        IslandMember target = island.findMember(targetUuid).orElse(null);
        IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
        if (target == null) {
            ASMessages.MEMBER_NOT_FOUND.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        if (target.isOwner() || senderMember == null) {
            ASMessages.MEMBER_HIGHER_ROLE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        // Non-owners must outrank their target to demote them.
        if (!senderMember.isOwner() && senderMember.role() != null && target.role() != null
            && senderMember.role().weight() <= target.role().weight()) {
            ASMessages.MEMBER_HIGHER_ROLE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }

        List<IslandRole> ladder = memberRoleLadder(island);
        int idx = findRoleIndex(ladder, target.role());
        if (idx <= 0) { // already at lowest role
            ASMessages.MEMBER_ALREADY_LOWEST_ROLE.message(sender, placeholders);
            return CompletableFuture.completedFuture(null);
        }
        IslandRole prev = ladder.get(idx - 1);

        placeholders.registerPlaceholder(prev);
        return repository.setRole(island.uniqueId(), targetUuid, prev.id())
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        ASMessages.UNEXPECTED_ERROR.message(sender, placeholders);
                        plugin.getSLF4JLogger().error("Failed to demote member {} on island {}: {}", targetUuid, island.uniqueId(), ex.getMessage(), ex);
                        return;
                    }

                    // Notify sender
                    ASMessages.MEMBER_DEMOTED_SENDER.message(sender, placeholders);

                    // Notify demoted member
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(targetUuid, ASMessages.MEMBER_DEMOTED_TARGET.component(placeholders));
                })
                .thenAccept(v -> {
                });
    }

    /**
     * Transfers island ownership to an existing member. Only the current owner may invoke this.
     * The ex-owner is demoted to the highest non-owner role; the new owner is promoted to the
     * owner slot transactionally. Messages the caller on any failed check and notifies the new
     * owner cross-server on success.
     */
    public CompletableFuture<Void> transfer(Island island, Player currentOwner, IslandMember newOwner) {
        PlaceholderContainer placeholders = AstralPaperAPI.createPlaceholderContainer(currentOwner)
                .registerPlaceholder(island)
                .registerDirect("target", new MinecraftPlayerPlaceholder(newOwner.playerUuid()));

        if (island.owner() == null || !island.owner().playerUuid().equals(currentOwner.getUniqueId())) {
            ASMessages.NOT_ISLAND_OWNER.message(currentOwner);
            return CompletableFuture.completedFuture(null);
        }
        IslandRole highestRole = island.roles().stream()
                .filter(r -> r.kind() == IslandRole.Type.MEMBER)
                .max(Comparator.comparingInt(IslandRole::weight))
                .orElseThrow(() -> new IllegalStateException("No MEMBER roles on island: " + island.uniqueId()));
        return repository.transferOwnership(
                        island.uniqueId(), currentOwner.getUniqueId(), highestRole.id(), newOwner.playerUuid())
                .whenComplete((success, ex) -> {
                    if (ex != null || !Boolean.TRUE.equals(success)) {
                        ASMessages.UNEXPECTED_ERROR.message(currentOwner, placeholders);
                        plugin.getSLF4JLogger().error("Failed to transfer ownership of island {} to {}: {}",
                                island.uniqueId(), newOwner.playerUuid(), ex != null ? ex.getMessage() : "transaction returned " + success, ex);
                        return;
                    }

                    // Notify ex-owner
                    ASMessages.OWNERSHIP_TRANSFERRED_SENDER.message(currentOwner, placeholders);

                    // Notify new owner
                    AstralPaperAPI.getService(ChatService.class)
                            .orElseThrow()
                            .sendMessage(newOwner.playerUuid(), ASMessages.OWNERSHIP_TRANSFERRED_TARGET.component(placeholders));
                })
                .thenAccept(v -> {
                });
    }

    // =========================================================================
    //  Cross-server packet handlers
    // =========================================================================

    /**
     * Receives a {@link MemberJoinPacket} broadcast by the originating server. Fires
     * {@link IslandMemberJoinEvent} locally so that listeners on this server (e.g. notification
     * handlers) are aware of the join. Cache coherence is maintained separately by the
     * {@code MemberObjectUpdatePacket} exchange in {@link MemberRepository}.
     *
     * <p>Does NOT write to the database.
     */
    private void handleMemberJoinPacket(MemberJoinPacket packet) {
        if (plugin.islands() == null) return;
        plugin.islands()
                .repository()
                .findCachedById(packet.islandId())
                .ifPresent(island -> Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.getPluginManager().callEvent(
                                new IslandMemberJoinEvent(island, packet.playerId(), packet.invitedBy()))));
    }

    /**
     * Receives a {@link MemberLeavePacket} broadcast by the originating server. Fires
     * {@link IslandMemberLeaveEvent} locally so that listeners on this server can react
     * (e.g. teleport the player out, show a notification). Cache coherence is maintained
     * separately by the {@code MemberObjectDeletePacket} exchange in {@link MemberRepository}.
     *
     * <p>Does NOT write to the database.
     */
    private void handleMemberLeavePacket(MemberLeavePacket packet) {
        if (plugin.islands() == null) return;
        plugin.islands().repository()
                .findCachedById(packet.islandId())
                .ifPresent(island -> Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.getPluginManager().callEvent(
                                new IslandMemberLeaveEvent(island, packet.playerId(), packet.reason()))));
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Returns all non-owner roles sorted ascending by weight — i.e. lowest privilege first.
     * Used as the promote/demote ladder.
     */
    private List<IslandRole> memberRoleLadder(Island island) {
        return island.roles().stream()
                .filter(r -> r.kind() == IslandRole.Type.MEMBER)
                .sorted(Comparator.comparingInt(IslandRole::weight))
                .toList();
    }

    private int findRoleIndex(List<IslandRole> ladder, IslandRole role) {
        if (role == null) return -1;
        for (int i = 0; i < ladder.size(); i++)
            if (ladder.get(i).id().equals(role.id())) return i;
        return -1;
    }
}
