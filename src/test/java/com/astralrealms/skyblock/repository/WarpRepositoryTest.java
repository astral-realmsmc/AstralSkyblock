package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandWarp;
import com.astralrealms.skyblock.model.island.WarpKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WarpRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesWarpsByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        WarpRepository repo = new WarpRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandWarp warp = new IslandWarp(island, "home", 1, 2, 3, 0f, 0f, false, 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(warp)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new WarpKey(island, "home"));
        assertThat(repo.findCachedById(new WarpKey(island, "home"))).isPresent();
    }
}
