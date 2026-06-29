package com.astralrealms.skyblock.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.repository.MemberRepository;

public class MemberService {

    private final AstralSkyblock plugin;
    private final MemberRepository repository;

    public MemberService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new MemberRepository(plugin);
    }

    public CompletableFuture<IslandMember> addOwner(UUID islandId, UUID ownerId) {
        return this.repository.addOwner(islandId, ownerId);
    }



}
