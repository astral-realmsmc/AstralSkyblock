package com.astralrealms.skyblock.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.support.PluginTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class IndexedSyncedRepositoryTest {

    /** A row with its own id (K=UUID) grouped under an island id (I=UUID). */
    record Row(UUID id, UUID islandId) {
    }

    /** In-memory concrete repository exercising the base's index behavior without a real DB. */
    static class TestRepo extends IndexedSyncedRepository<UUID, Row, UUID> {
        final Map<UUID, Row> table = new ConcurrentHashMap<>();

        TestRepo(AstralSkyblock plugin) {
            super(plugin, "skyblock:test", "skyblock.test.update", Row.class);
        }

        @Override protected UUID indexKeyOf(Row v) { return v.islandId(); }
        @Override protected UUID keyFromValue(Row v) { return v.id(); }
        @Override protected String cacheKey(UUID key) { return this.cacheKey + ":" + key; }

        @Override protected CompletableFuture<Row> loadById(UUID key) {
            return CompletableFuture.completedFuture(table.get(key));
        }
        @Override protected CompletableFuture<List<Row>> loadByIndex(UUID islandId) {
            return CompletableFuture.completedFuture(
                    table.values().stream().filter(r -> r.islandId().equals(islandId)).toList());
        }
        @Override protected CompletableFuture<Row> saveToDatabase(Row v) {
            table.put(v.id(), v);
            return CompletableFuture.completedFuture(v);
        }
        @Override protected CompletableFuture<Void> deleteFromDatabase(UUID key) {
            table.remove(key);
            return CompletableFuture.completedFuture(null);
        }
        @Override protected void publishUpdate(UUID key, Row v) { }
        @Override protected void publishInvalidation(UUID key) { }
    }

    AstralSkyblock plugin;
    TestRepo repo;
    UUID island;

    @BeforeEach
    void setUp() {
        plugin = PluginTestSupport.mockPlugin();
        repo = new TestRepo(plugin);
        island = UUID.randomUUID();
    }

    @Test
    void loaderPathPopulatesTheIndex() {
        UUID id = UUID.randomUUID();
        repo.table.put(id, new Row(id, island));

        repo.findById(id).join(); // read-through loader path (bypasses cacheLocally)

        assertThat(repo.keysIn(island)).containsExactly(id);
    }

    @Test
    void primeReplacesSliceAtomicallyAndPopulatesL1() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.table.put(b, new Row(b, island));

        repo.prime(island).join();

        assertThat(repo.keysIn(island)).containsExactlyInAnyOrder(a, b);
        assertThat(repo.findCachedById(a)).isPresent();
        assertThat(repo.findCachedById(b)).isPresent();
    }

    @Test
    void evictIndexClearsSliceAndL1() {
        UUID a = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.prime(island).join();

        repo.evictIndex(island);

        assertThat(repo.keysIn(island)).isEmpty();
        assertThat(repo.findCachedById(a)).isEmpty();
    }

    @Test
    void invalidateRemovesKeyFromIndex() {
        UUID a = UUID.randomUUID();
        repo.table.put(a, new Row(a, island));
        repo.prime(island).join();

        repo.invalidateLocally(a);

        assertThat(repo.keysIn(island)).isEmpty();
    }
}
