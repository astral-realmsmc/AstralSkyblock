package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.role.IslandRole;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RoleRepositoryTest {

    AstralSkyblock plugin;
    DatabaseService database;
    RoleRepository repo;
    UUID island;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        database = plugin.database();
        repo = new RoleRepository(plugin);
        island = UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesRoleIdsForIsland() {
        IslandRole role = new IslandRole(7L, island, IslandRole.Type.MEMBER, "Member", 10, true, 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(role)));

        repo.prime(island).join();

        assertThat(repo.getIslandRoleIds(island)).containsExactly(7L);
    }
}
