package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.IslandUpgrade;
import com.astralrealms.skyblock.model.island.UpgradeKey;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class UpgradeRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void primeIndexesUpgradesByIsland() {
        AstralSkyblock plugin = PluginTestSupport.mockPlugin();
        DatabaseService database = plugin.database();
        UpgradeRepository repo = new UpgradeRepository(plugin);
        UUID island = UUID.randomUUID();
        IslandUpgrade upgrade = new IslandUpgrade(island, "crop_growth", 3);
        when(database.supply(any())).thenReturn((CompletableFuture) CompletableFuture.completedFuture(List.of(upgrade)));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactly(new UpgradeKey(island, "crop_growth"));
    }
}
