package com.astralrealms.skyblock.service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.event.IslandMemberJoinEvent;
import com.astralrealms.skyblock.event.IslandMemberLeaveEvent;
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
     * and outrank the target (owners always outrank everyone). Silently returns if any check fails.
     */
    public CompletableFuture<Void> kick(Island island, Player kicker, UUID targetUuid) {
        if (!island.hasPermission(kicker, IslandPermission.KICK_MEMBER))
            return CompletableFuture.completedFuture(null);
        IslandMember target = island.findMember(targetUuid).orElse(null);
        if (target == null || target.isOwner()) return CompletableFuture.completedFuture(null);

        IslandMember kickerMember = island.findMember(kicker.getUniqueId()).orElse(null);
        // Non-owners must outrank their target; owners (role == null) may kick anyone.
        if (kickerMember != null && !kickerMember.isOwner()
                && kickerMember.role() != null && target.role() != null
                && kickerMember.role().weight() <= target.role().weight())
            return CompletableFuture.completedFuture(null);

        return repository.remove(island.uniqueId(), targetUuid)
                .thenAccept(v -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                                    island, targetUuid, IslandMemberLeaveEvent.Reason.KICKED)));
                    plugin.messaging().send(ASConstants.MEMBER_SYNC_CHANNEL, new MemberLeavePacket(
                            island.uniqueId(), targetUuid, IslandMemberLeaveEvent.Reason.KICKED));
                });
    }

    /**
     * Voluntarily removes the player from their island. Blocked if the player is the island owner.
     * Silently returns if the player is not a member or is the owner.
     */
    public CompletableFuture<Void> leave(Island island, Player player) {
        IslandMember member = island.findMember(player.getUniqueId()).orElse(null);
        if (member == null || member.isOwner()) return CompletableFuture.completedFuture(null);
        return repository.remove(island.uniqueId(), player.getUniqueId())
                .thenAccept(v -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getPluginManager().callEvent(new IslandMemberLeaveEvent(
                                    island, player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY)));
                    plugin.messaging().send(ASConstants.MEMBER_SYNC_CHANNEL, new MemberLeavePacket(
                            island.uniqueId(), player.getUniqueId(), IslandMemberLeaveEvent.Reason.VOLUNTARY));
                });
    }

    /**
     * Promotes a member one step up the role ladder. The sender must have
     * {@link IslandPermission#PROMOTE_MEMBERS} and must outrank the resulting role (non-owners cannot
     * promote above themselves). Silently returns on any check failure or if the target is already
     * at the top of the ladder.
     */
    public CompletableFuture<Void> promote(Island island, Player sender, UUID targetUuid) {
        if (!island.hasPermission(sender, IslandPermission.PROMOTE_MEMBERS))
            return CompletableFuture.completedFuture(null);
        IslandMember target = island.findMember(targetUuid).orElse(null);
        IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
        if (target == null || target.isOwner() || senderMember == null)
            return CompletableFuture.completedFuture(null);

        List<IslandRole> ladder = memberRoleLadder(island);
        int idx = findRoleIndex(ladder, target.role());
        if (idx < 0 || idx >= ladder.size() - 1) return CompletableFuture.completedFuture(null);
        IslandRole next = ladder.get(idx + 1);
        // Non-owners cannot promote to a role at or above their own weight.
        if (!senderMember.isOwner() && senderMember.role() != null
                && next.weight() >= senderMember.role().weight())
            return CompletableFuture.completedFuture(null);

        return repository.setRole(island.uniqueId(), targetUuid, next.id())
                .thenAccept(v -> {});
    }

    /**
     * Demotes a member one step down the role ladder. The sender must have
     * {@link IslandPermission#DEMOTE_MEMBERS}. Silently returns if the target is already at the
     * lowest (default) role or if any permission check fails.
     */
    public CompletableFuture<Void> demote(Island island, Player sender, UUID targetUuid) {
        if (!island.hasPermission(sender, IslandPermission.DEMOTE_MEMBERS))
            return CompletableFuture.completedFuture(null);
        IslandMember target = island.findMember(targetUuid).orElse(null);
        IslandMember senderMember = island.findMember(sender.getUniqueId()).orElse(null);
        if (target == null || target.isOwner() || senderMember == null)
            return CompletableFuture.completedFuture(null);

        // Non-owners must outrank their target to demote them.
        if (!senderMember.isOwner() && senderMember.role() != null && target.role() != null
                && senderMember.role().weight() <= target.role().weight())
            return CompletableFuture.completedFuture(null);

        List<IslandRole> ladder = memberRoleLadder(island);
        int idx = findRoleIndex(ladder, target.role());
        if (idx <= 0) return CompletableFuture.completedFuture(null); // already at lowest role
        IslandRole prev = ladder.get(idx - 1);

        return repository.setRole(island.uniqueId(), targetUuid, prev.id())
                .thenAccept(v -> {});
    }

    /**
     * Transfers island ownership to an existing member. Only the current owner may invoke this.
     * The ex-owner is demoted to the highest non-owner role; the new owner is promoted to the
     * owner slot transactionally.
     */
    public CompletableFuture<Void> transfer(Island island, Player currentOwner, IslandMember newOwner) {
        if (island.owner() == null || !island.owner().playerUuid().equals(currentOwner.getUniqueId()))
            return CompletableFuture.completedFuture(null);
        IslandRole highestRole = island.roles().stream()
                .filter(r -> r.kind() == IslandRole.Type.MEMBER)
                .max(Comparator.comparingInt(IslandRole::weight))
                .orElseThrow(() -> new IllegalStateException("No MEMBER roles on island: " + island.uniqueId()));
        return repository.transferOwnership(
                        island.uniqueId(), currentOwner.getUniqueId(), highestRole.id(), newOwner.playerUuid())
                .thenAccept(v -> {});
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
        plugin.islands().repository()
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
