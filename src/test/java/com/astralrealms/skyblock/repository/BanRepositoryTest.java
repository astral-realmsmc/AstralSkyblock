package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.member.IslandBan;
import com.astralrealms.skyblock.model.member.IslandPlayerKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class BanRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesBansByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        BanRepository repo = new BanRepository(plugin);
        UUID island = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        IslandBan ban = new IslandBan(island, player, UUID.randomUUID(), "griefing", 0L);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(ban)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new IslandPlayerKey(island, player));
    }
}
