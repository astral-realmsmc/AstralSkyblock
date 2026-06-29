package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandMember;
import com.astralrealms.skyblock.model.member.MemberKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MemberRepositoryTest {

    AstralSkyblock plugin;
    DatabaseService database;
    MemberRepository repo;
    UUID island;
    UUID player;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        database = plugin.database();
        repo = new MemberRepository(plugin);
        island = UUID.randomUUID();
        player = UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void primePopulatesBothIslandAndPlayerIndexes() {
        IslandMember member = new IslandMember(island, player, true, null, 0L);
        // loadByIndex runs database.supply(...); ignore the lambda and return the canned list.
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(member)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new MemberKey(island, player));
        assertThat(repo.findPlayerIslands(player)).containsExactly(island);
    }
}
