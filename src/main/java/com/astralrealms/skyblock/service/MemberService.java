package com.astralrealms.skyblock.service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.repository.MemberRepository;

public class MemberService {

    private final AstralSkyblock plugin;
    private final MemberRepository repository;

    public MemberService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new MemberRepository(plugin);
    }

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
}
