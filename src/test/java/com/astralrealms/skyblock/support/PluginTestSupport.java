package com.astralrealms.skyblock.support;

import java.util.concurrent.CompletableFuture;

import com.astralrealms.core.cache.CacheService;
import com.astralrealms.core.messaging.MessagingService;
import com.astralrealms.core.storage.DatabaseService;
import com.astralrealms.skyblock.AstralSkyblock;
import org.mockito.MockMakers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Builds a Mockito-stubbed {@link AstralSkyblock} suitable for unit-testing repositories without a
 * real database, Redis, or messaging backend. L2 (Redis) reads always miss, so the loader falls
 * through to {@code loadById}; L2 writes and message sends are no-ops.
 */
public final class PluginTestSupport {

    private PluginTestSupport() {
    }

    public static AstralSkyblock mockPlugin() {
        AstralSkyblock plugin = mock(AstralSkyblock.class);

        // Use subclass mock maker for non-final service classes (inline mock maker fails
        // to retransform classes with static initialisers on JDK 26+).
        CacheService cache = mock(CacheService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        lenient().when(cache.get(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(cache.set(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("OK"));
        lenient().when(cache.set(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture("OK"));
        lenient().when(cache.del(anyString())).thenReturn(CompletableFuture.completedFuture(1L));

        MessagingService messaging = mock(MessagingService.class, withSettings().mockMaker(MockMakers.SUBCLASS));

        DatabaseService database = mock(DatabaseService.class, withSettings().mockMaker(MockMakers.SUBCLASS));

        lenient().when(plugin.cache()).thenReturn(cache);
        lenient().when(plugin.messaging()).thenReturn(messaging);
        lenient().when(plugin.database()).thenReturn(database);
        return plugin;
    }

    public static DatabaseService database(AstralSkyblock plugin) {
        return plugin.database();
    }
}
