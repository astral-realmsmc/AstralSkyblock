package com.astralrealms.skyblock.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import com.astralrealms.core.cache.CacheRepository;
import com.astralrealms.core.paper.AstralPaperAPI;
import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.IslandServer;
import com.astralrealms.skyblock.utils.ASConstants;

import io.lettuce.core.ScriptOutputType;

/**
 * Tracks the live island servers (a heartbeat per server) and which server hosts each island.
 *
 * <p>The island → server mapping is the only thing that keeps an island world loaded on a single
 * server, so every write to it is conditional:
 * <ul>
 *     <li>a server is {@linkplain #claimHost claimed} with {@code HSETNX} before the world is
 *     loaded, as {@code <server>|loading} — whoever loses the race routes to the winner;</li>
 *     <li>once loaded, the host {@linkplain #confirmHost confirms} the entry, which only succeeds
 *     while the entry is absent or still its own;</li>
 *     <li>a server only ever {@linkplain #releaseHost releases} its own entry (compare-and-delete).</li>
 * </ul>
 * A crashed server's entries are detected through its expired heartbeat and dropped by the next
 * reader; a restarted server drops its own leftovers on startup.
 */
public class ServerService {

    private static final String LOADING_SUFFIX = "|loading";
    // Deleted islands. Lets a host that missed the delete broadcast (reconnecting queue) find out,
    // instead of saving the world back into storage. Island ids are never reused.
    private static final String TOMBSTONE_PREFIX = "skyblock:island-tombstones:";
    private static final Duration TOMBSTONE_TTL = Duration.ofDays(30);
    // Islands a player was just routed to: the host must not idle-unload them under the arrival.
    private static final String ARRIVAL_PREFIX = "skyblock:island-arrivals:";
    private static final Duration ARRIVAL_TTL = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_TTL = Duration.ofMinutes(1);
    private static final long STARTUP_CLEANUP_TIMEOUT_SECONDS = 5;

    // HSET field ARGV[2] when the field is absent or holds ARGV[2] / ARGV[3]; 1 when written.
    private static final String CONFIRM_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if (not current) or current == ARGV[2] or current == ARGV[3] then
                redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
                return 1
            end
            return 0
            """;
    // HDEL the field when it holds ARGV[2] or ARGV[3]; 1 when deleted.
    private static final String RELEASE_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current and (current == ARGV[2] or current == ARGV[3]) then
                return redis.call('HDEL', KEYS[1], ARGV[1])
            end
            return 0
            """;

    private final AstralSkyblock plugin;
    private final CacheRepository<IslandServer> repository;
    private BukkitTask heartbeatTask;

    public ServerService(AstralSkyblock plugin) {
        this.plugin = plugin;
        this.repository = new CacheRepository<>(plugin.cache(), ASConstants.SERVER_CACHE_KEY, IslandServer.class, HEARTBEAT_TTL);

        if (this.plugin.configuration().isIslandServer()) {
            // Server ids are stable across restarts: anything still mapped to us describes worlds
            // the previous run had loaded, and nothing is loaded yet.
            releaseOwnHosts();
            this.heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::update, 0, 300);
        }
    }

    /** Republishes the heartbeat now, so placements see a load or unload without waiting 15 s. */
    public void refreshHeartbeat() {
        if (this.heartbeatTask == null)
            return; // not an island server, or shutting down
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this::update);
    }

    /** Records that {@code island} was deleted. */
    public CompletableFuture<Void> markDeleted(UUID island) {
        return this.plugin.cache()
                .set(TOMBSTONE_PREFIX + island, "1", TOMBSTONE_TTL)
                .thenApply(ignored -> null);
    }

    /** Whether {@code island} was deleted, possibly while this server missed the broadcast. */
    public CompletableFuture<Boolean> isDeleted(UUID island) {
        return this.plugin.cache().exists(TOMBSTONE_PREFIX + island);
    }

    /** Notes that a player is on their way to {@code island}'s host. Best effort. */
    public void markArriving(UUID island) {
        this.plugin.cache()
                .set(ARRIVAL_PREFIX + island, "1", ARRIVAL_TTL)
                .exceptionally(throwable -> {
                    this.plugin.getSLF4JLogger().warn("Failed to record an arrival on island {}", island, throwable);
                    return null;
                });
    }

    /** Whether a player was routed to {@code island} in the last few seconds. */
    public CompletableFuture<Boolean> isArriving(UUID island) {
        return this.plugin.cache().exists(ARRIVAL_PREFIX + island);
    }

    private void update() {
        this.repository.set(new IslandServer(
                localId(),
                plugin.worlds().getLoadedWorlds().size(),
                plugin.configuration().maximumIslands()
        )).exceptionally(throwable -> {
            this.plugin.getSLF4JLogger().error("Failed to publish island server heartbeat", throwable);
            return null;
        });
    }

    /**
     * Stops advertising this server. Called first thing on disable so no new island is placed here
     * while the worlds are being flushed.
     */
    public void shutdown() {
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel();
            this.heartbeatTask = null;
        }
        if (!this.plugin.configuration().isIslandServer())
            return;
        try {
            this.repository.delete(localId()).get(STARTUP_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().warn("Failed to withdraw the island server heartbeat on shutdown", e);
        }
    }

    public CompletableFuture<IslandServer> findEmptiestServer() {
        return this.repository.findAll()
                .thenApply(servers -> servers.stream()
                        .filter(server -> server.loadedIslands() < server.maximumIslands())
                        .min(Comparator.comparingInt(IslandServer::loadedIslands))
                        .orElse(null));
    }

    /** Whether {@code server} has published a heartbeat within the last minute. */
    public CompletableFuture<Boolean> isAlive(UUID server) {
        if (server.equals(localId()))
            return CompletableFuture.completedFuture(this.plugin.configuration().isIslandServer());
        return this.repository.exists(server);
    }

    /** The server hosting (or loading) {@code island}, or {@code null} when none does. */
    public CompletableFuture<@Nullable Host> findHost(UUID island) {
        return this.plugin.cache()
                .hget(ASConstants.ISLAND_SERVER_KEY, island.toString())
                .thenApply(value -> value.map(Host::parse).orElse(null));
    }

    /**
     * Reserves {@code island} for {@code server} before its world is loaded. Completes with
     * {@code false} when another server already holds it.
     */
    public CompletableFuture<Boolean> claimHost(UUID island, UUID server) {
        return this.plugin.cache().runAsync(commands -> commands
                .hsetnx(ASConstants.ISLAND_SERVER_KEY, island.toString(), server + LOADING_SUFFIX)
                .toCompletableFuture());
    }

    /**
     * Marks {@code island} as loaded on {@code server}. Completes with {@code false} when another
     * server holds it, in which case the caller's copy is a duplicate and must not be kept.
     */
    public CompletableFuture<Boolean> confirmHost(UUID island, UUID server) {
        return this.plugin.cache().runAsync(commands -> commands
                .<Long>eval(CONFIRM_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{ASConstants.ISLAND_SERVER_KEY},
                        island.toString(), server.toString(), server + LOADING_SUFFIX)
                .toCompletableFuture()
                .thenApply(written -> written != null && written == 1L));
    }

    /** Drops {@code server}'s entry for {@code island}, whether loading or loaded. */
    public CompletableFuture<Void> releaseHost(UUID island, UUID server) {
        return release(island, server.toString(), server + LOADING_SUFFIX);
    }

    /**
     * Drops {@code server}'s entry for {@code island} only while it is still loading: a requester
     * giving up on a slow load must not erase a world that did come up in the meantime.
     */
    public CompletableFuture<Void> releaseLoadingClaim(UUID island, UUID server) {
        String loading = server + LOADING_SUFFIX;
        return release(island, loading, loading);
    }

    /** Drops whatever entry {@code island} has. Only for a deleted island. */
    public CompletableFuture<Void> clearHost(UUID island) {
        return this.plugin.cache()
                .hdel(ASConstants.ISLAND_SERVER_KEY, island.toString())
                .thenApply(ignored -> null);
    }

    private CompletableFuture<Void> release(UUID island, String value, String alternative) {
        return this.plugin.cache().runAsync(commands -> commands
                .<Long>eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{ASConstants.ISLAND_SERVER_KEY},
                        island.toString(), value, alternative)
                .toCompletableFuture()
                .thenApply(ignored -> null));
    }

    private void releaseOwnHosts() {
        UUID self = localId();
        try {
            Map<String, String> entries = this.plugin.cache()
                    .hgetall(ASConstants.ISLAND_SERVER_KEY)
                    .get(STARTUP_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> stale = entries.entrySet().stream()
                    .filter(entry -> self.equals(Host.parse(entry.getValue()).server()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (stale.isEmpty())
                return;

            CompletableFuture.allOf(stale.stream()
                            .map(island -> releaseHost(UUID.fromString(island), self))
                            .toArray(CompletableFuture[]::new))
                    .get(STARTUP_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.plugin.getSLF4JLogger().info("Released {} island host mapping(s) left over from the previous run", stale.size());
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to release island host mappings left over from the previous run", e);
        }
    }

    private static UUID localId() {
        return AstralPaperAPI.serverInformation().uniqueId();
    }

    /** An island's host entry: the server, and whether its world is still being loaded there. */
    public record Host(UUID server, boolean loading) {

        static Host parse(String value) {
            boolean loading = value.endsWith(LOADING_SUFFIX);
            String id = loading ? value.substring(0, value.length() - LOADING_SUFFIX.length()) : value;
            return new Host(UUID.fromString(id), loading);
        }
    }
}
