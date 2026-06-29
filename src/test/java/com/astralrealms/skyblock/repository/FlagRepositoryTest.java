package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.FlagKey;
import com.astralrealms.skyblock.model.island.IslandFlag;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FlagRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesFlagsByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        FlagRepository repo = new FlagRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandFlag flag = new IslandFlag(island, "pvp", false);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(flag)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new FlagKey(island, "pvp"));
    }
}
