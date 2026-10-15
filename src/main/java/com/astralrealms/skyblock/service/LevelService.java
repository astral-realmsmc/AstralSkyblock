package com.astralrealms.skyblock.service;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Hopper;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.event.island.IslandLevelChangedEvent;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;

/**
 * Scores islands: sums the value of every block inside an island's border (from
 * {@code block-values.yml}) into the island's {@code value}, and derives its {@code level} from
 * the configured points per level.
 *
 * <p>Scans only read chunks the island actually has — the slime world's chunk storage plus whatever
 * is loaded — never the empty void filling the rest of the border box. Each island keeps an
 * {@link IslandScore}: the value of every chunk it scanned and the chunks whose blocks changed
 * since. A full scan ({@code /is calc}, or the first rescan after the world loads) reads every
 * chunk; the periodic rescan afterwards reads only the dirty ones and adds the rest from cache.
 * Loading a world scans nothing: the stored value stands until something changes.
 *
 * <p>An island is scored across all its worlds: its overworld and every dimension it has. Each world
 * keeps its own {@link IslandScore}, since they share chunk coordinates, and they are scanned one
 * after the other. A scan is refused while one of the island's dimensions is not loaded here, rather
 * than dropping that dimension's blocks from the score.
 *
 * <p>Chunks are captured on the main thread within a per-tick time budget and summed off it, so a
 * large island costs a slice of several ticks instead of a freeze. Only the server hosting the
 * island world can scan it, which is also the server that runs the periodic rescan.
 */
public class LevelService {

    /**
     * Ceiling on the covered radius, in chunks, so a misconfigured border cannot walk the whole
     * world. 160 chunks (2560 blocks) covers the largest shipped border (5000 across); a bigger one
     * is clamped with a warning rather than silently.
     */
    private static final int MAX_CHUNK_RADIUS = 160;
    /** Rescan intervals a pass may span before the timer assumes it is stuck and takes the latch back. */
    private static final int STUCK_PASS_INTERVALS = 4;

    private final AstralSkyblock plugin;
    // Islands with a scan in flight, so a second /is calc cannot start a parallel scan.
    private final Set<UUID> scanning = ConcurrentHashMap.newKeySet();
    // Summing runs on Bukkit's async pool rather than the JVM-wide ForkJoin common pool, which
    // every plugin on the server shares.
    private final Executor asyncExecutor = this::runAsync;
    // islandId -> epoch millis of its last completed full scan, for the /is calc cooldown.
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();
    // Scoring state of the islands hosted here, by island then world name; dropped when they unload.
    private final Map<UUID, Map<String, IslandScore>> scores = new ConcurrentHashMap<>();
    private volatile List<Island> top = List.of();
    // Whether a rescan pass is walking the hosted islands, so the timer cannot start a second one.
    private final AtomicBoolean rescanning = new AtomicBoolean();
    private final AtomicInteger rescanGeneration = new AtomicInteger();
    private volatile long rescanStartedAt;

    public LevelService(AstralSkyblock plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the background jobs: a periodic rescan of the islands hosted here (island servers
     * only) and a periodic refresh of the leaderboard.
     */
    public void load() {
        int rescanSeconds = this.plugin.configuration().level().rescanIntervalSeconds();
        if (rescanSeconds > 0 && this.plugin.configuration().isIslandServer()) {
            long ticks = rescanSeconds * 20L;
            Bukkit.getScheduler().runTaskTimer(this.plugin, this::rescanHostedIslands, ticks, ticks);
        }

        long topTicks = Math.max(1, this.plugin.configuration().level().topRefreshSeconds()) * 20L;
        Bukkit.getScheduler().runTaskTimer(this.plugin, this::refreshTop, 20L, topTicks);
    }

    // =========================================================================
    //  Scanning
    // =========================================================================

    private void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, task);
    }

    /** Notes that blocks of an island changed in a chunk of one of its worlds, so the next periodic pass rescans it. */
    public void markDirty(UUID islandId, String world, int chunkX, int chunkZ) {
        score(islandId, world).markDirty(chunkKey(chunkX, chunkZ));
    }

    /** Forgets an island's scan bookkeeping. Called when its world unloads here. */
    public void forget(UUID islandId) {
        this.lastScan.remove(islandId);
        this.scores.remove(islandId);
    }

    private IslandScore score(UUID islandId, String world) {
        return this.scores.computeIfAbsent(islandId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(world, ignored -> new IslandScore());
    }

    private boolean isDirty(UUID islandId) {
        Map<String, IslandScore> worlds = this.scores.get(islandId);
        return worlds != null && worlds.values().stream().anyMatch(IslandScore::isDirty);
    }

    /**
     * Fully rescans an island and persists its new value and level. Fails when the island world is
     * not loaded on this server (only its host can read its blocks) or when a scan is already
     * running for it.
     */
    public CompletableFuture<Long> calculate(Island island) {
        return scan(island, true);
    }

    /**
     * Scans an island: every chunk it has when {@code full}, otherwise only the chunks changed since
     * the last scan. Runs a full scan anyway when there is no baseline to add a partial one to.
     */
    private CompletableFuture<Long> scan(Island island, boolean full) {
        UUID islandId = island.uniqueId();
        List<SlimeWorldInstance> instances = this.plugin.worlds().islandInstances(islandId);
        if (instances.isEmpty() || !this.plugin.worlds().isFullyLoaded(islandId))
            return CompletableFuture.failedFuture(new NotHostedException(islandId));
        if (!this.scanning.add(islandId))
            return CompletableFuture.failedFuture(new ScanInProgressException(islandId));

        // Hoppers seen by every world's scan, in case the island's hoppers were never counted.
        Map<String, Map<Long, Integer>> hoppers = new HashMap<>();
        AtomicBoolean everyWorldFull = new AtomicBoolean(true);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SlimeWorldInstance instance : instances)
            chain = chain.thenCompose(ignored -> scanWorld(island, instance, full, hoppers, everyWorldFull));

        return chain
                .thenApply(ignored -> {
                    // Only when the load-time hopper census failed: a full scan has seen every chunk.
                    // Runs on the main thread, where the last world's scan completed.
                    if (everyWorldFull.get() && !this.plugin.blockLimits().isCounted(islandId))
                        this.plugin.blockLimits().seed(islandId, hoppers);
                    return total(islandId);
                })
                .thenCompose(value -> persist(island, value).thenApply(ignored -> value))
                .whenComplete((value, throwable) -> {
                    this.scanning.remove(islandId);
                    if (throwable == null && full)
                        this.lastScan.put(islandId, System.currentTimeMillis());
                });
    }

    /** The island's value: the sum of its worlds' scanned chunks. */
    private long total(UUID islandId) {
        long total = 0;
        for (IslandScore score : this.scores.getOrDefault(islandId, Map.of()).values())
            total += score.total();
        return total;
    }

    /** Scans one of the island's worlds, completing once its score is updated. */
    private CompletableFuture<Void> scanWorld(Island island, SlimeWorldInstance instance, boolean full,
                                              Map<String, Map<Long, Integer>> hoppers, AtomicBoolean everyWorldFull) {
        World world = instance.getBukkitWorld();
        if (world == null)
            return CompletableFuture.failedFuture(new NotHostedException(island.uniqueId()));

        Scan scan;
        try {
            IslandScore score = score(island.uniqueId(), world.getName());
            boolean fullScan = full || !score.hasBaseline();
            if (!fullScan)
                everyWorldFull.set(false);
            BorderBox border = borderBox(island);

            Set<Long> candidates = fullScan ? existingChunks(instance, score) : score.dirtyChunks();
            List<Long> chunks = new ArrayList<>(candidates.size());
            List<Long> outside = new ArrayList<>();
            for (long chunk : candidates)
                (border.contains(chunk) ? chunks : outside).add(chunk);
            // Changes outside the border can never be scored; keeping them dirty would only make the
            // island look changed forever.
            score.discardDirty(outside);

            scan = new Scan(island.uniqueId(), world, score, chunks, fullScan,
                    hoppers.computeIfAbsent(world.getName(), ignored -> new HashMap<>()));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<Long> result = new CompletableFuture<>();
        resume(result, () -> processBatch(scan, result));
        return result.thenApply(ignored -> null);
    }

    /**
     * Captures the next chunks — as many as the tick's time budget allows, with at most
     * {@code chunks-per-batch} chunk loads in flight — and schedules itself once they are summed,
     * until the scan runs out of chunks. Runs on the main thread; only the block summing happens off
     * it.
     */
    private void processBatch(Scan scan, CompletableFuture<Long> result) {
        // The idle sweep (or a delete) can unload the world under a running scan; abort rather than
        // read blocks out of a world that is no longer there. The island simply keeps its old score.
        if (Bukkit.getWorld(scan.world.getName()) == null) {
            fail(scan, result, new NotHostedException(scan.islandId));
            return;
        }

        if (scan.cursor >= scan.chunks.size()) {
            scan.score.apply(scan.values, scan.full);
            this.plugin.getSLF4JLogger().debug("Scanned world {} of island {}: {} chunk(s), full = {}, {} ms",
                    scan.world.getName(), scan.islandId, scan.chunks.size(), scan.full, (System.nanoTime() - scan.startedAt) / 1_000_000);
            result.complete(scan.score.total());
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.plugin.configuration().level().scanBudgetMillis());
        int maxLoads = this.plugin.configuration().level().chunksPerBatch();
        List<Captured> ready = new ArrayList<>();
        List<CompletableFuture<Captured>> loading = new ArrayList<>();
        do {
            long key = scan.chunks.get(scan.cursor++);
            int chunkX = chunkX(key);
            int chunkZ = chunkZ(key);
            if (scan.world.isChunkLoaded(chunkX, chunkZ)) {
                ready.add(capture(scan, key, scan.world.getChunkAt(chunkX, chunkZ)));
                continue;
            }

            // gen = false: a chunk the island does not have holds nothing and must not be generated.
            // Paper completes this future on the main thread, which is where a capture must happen.
            loading.add(scan.world.getChunkAtAsync(chunkX, chunkZ, false)
                    .thenApply(chunk -> capture(scan, key, chunk))
                    .exceptionally(throwable -> {
                        // One unreadable chunk must not void the whole scan; it stays dirty instead.
                        this.plugin.getSLF4JLogger().warn("Skipped chunk {},{} while scanning island {}",
                                chunkX, chunkZ, scan.islandId, throwable);
                        scan.score.restoreDirty(List.of(key));
                        return null;
                    }));
        } while (scan.cursor < scan.chunks.size() && loading.size() < maxLoads && System.nanoTime() < deadline);

        CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    Map<Long, Long> values = new HashMap<>();
                    Map<Material, Integer> materialValues = this.plugin.blockValuesConfiguration().materialValues();
                    for (Captured captured : ready)
                        values.put(captured.chunk, captured.value(scan.world, materialValues));
                    for (CompletableFuture<Captured> future : loading) {
                        Captured captured = future.join();
                        if (captured != null)
                            values.put(captured.chunk, captured.value(scan.world, materialValues));
                    }
                    return values;
                }, this.asyncExecutor)
                .whenComplete((values, throwable) -> resume(result, () -> {
                    if (throwable != null) {
                        fail(scan, result, throwable);
                        return;
                    }

                    // Anything thrown here would otherwise be swallowed by the scheduler and leave
                    // `result` — and every latch waiting on it — hanging forever.
                    try {
                        scan.values.putAll(values);
                        processBatch(scan, result);
                    } catch (Exception exception) {
                        fail(scan, result, exception);
                    }
                }));
    }

    /**
     * Takes what a scan needs from a chunk while on the main thread: its block snapshot, the value of
     * its spawners (their entity type lives in a block state the snapshot cannot see) and its exact
     * hopper count, which reconciles {@link BlockLimitService} for this chunk. Both come from the
     * chunk's block entities, so no block walk is involved. A {@code null} chunk is one the island
     * does not have: it scores nothing.
     */
    private Captured capture(Scan scan, long key, @Nullable Chunk chunk) {
        scan.captured.add(key);
        // From here on, changes in this chunk are the next scan's business.
        scan.score.captured(key);
        if (chunk == null) {
            scan.hoppers.remove(key);
            this.plugin.blockLimits().reconcile(scan.islandId, scan.world.getName(), chunkX(key), chunkZ(key), 0);
            return new Captured(key, null, 0);
        }

        Map<EntityType, Integer> spawnerValues = this.plugin.blockValuesConfiguration().spawnerValues();
        int hoppers = 0;
        long spawners = 0;
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Hopper)
                hoppers++;
            else if (state instanceof CreatureSpawner spawner && spawner.getSpawnedType() != null) {
                Integer value = spawnerValues.get(spawner.getSpawnedType());
                if (value != null)
                    spawners += value;
            }
        }
        scan.hoppers.put(key, hoppers);
        this.plugin.blockLimits().reconcile(scan.islandId, scan.world.getName(), chunk.getX(), chunk.getZ(), hoppers);
        return new Captured(key, chunk.getChunkSnapshot(false, false, false), spawners);
    }

    /** Fails a scan, handing the chunks it captured back to the next one. */
    private void fail(Scan scan, CompletableFuture<Long> result, Throwable throwable) {
        scan.score.restoreDirty(scan.captured);
        result.completeExceptionally(throwable);
    }

    /**
     * Hands work back to the main thread. Scheduling itself throws once the plugin is disabling, and
     * that throw would land in a discarded completion stage — leaving the scan's future, and
     * everything waiting on it, hanging forever. It fails the scan instead.
     */
    private void resume(CompletableFuture<Long> result, Runnable body) {
        try {
            Bukkit.getScheduler().runTask(this.plugin, body);
        } catch (Exception exception) {
            result.completeExceptionally(exception);
        }
    }

    /**
     * The plain material value of a chunk's blocks. Spawners are skipped: they are valued by type,
     * from their block state, when the chunk is captured. Empty sections are skipped outright.
     */
    private static long sum(ChunkSnapshot chunkSnapshot, World world, Map<Material, Integer> values) {
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        long total = 0;
        for (int y = minHeight; y < maxHeight; y++) {
            if (chunkSnapshot.isSectionEmpty((y - minHeight) >> 4)) {
                y += 15 - ((y - minHeight) & 15); // jump to the end of the empty section
                continue;
            }

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material material = chunkSnapshot.getBlockType(x, y, z);
                    if (material == Material.AIR || material == Material.SPAWNER)
                        continue;
                    Integer value = values.get(material);
                    if (value != null)
                        total += value;
                }
            }
        }
        return total;
    }

    /**
     * Every chunk the island may hold blocks in: the slime world's stored chunks, the chunks loaded
     * right now (a chunk first generated this session may not be in storage yet) and any chunk the
     * score already knows or has marked dirty.
     */
    private static Set<Long> existingChunks(SlimeWorldInstance instance, IslandScore score) {
        Set<Long> chunks = score.knownChunks();
        for (SlimeChunk chunk : storedChunks(instance))
            chunks.add(chunkKey(chunk.getX(), chunk.getZ()));
        for (Chunk chunk : instance.getBukkitWorld().getLoadedChunks())
            chunks.add(chunkKey(chunk.getX(), chunk.getZ()));
        return chunks;
    }

    /**
     * A copy of the slime world's chunk storage. ASP keeps it in a plain map that its chunk loading
     * can touch while it is copied; a copy that trips over that is simply taken again.
     */
    static List<SlimeChunk> storedChunks(SlimeWorld world) {
        for (int attempt = 1; ; attempt++) {
            try {
                return new ArrayList<>(world.getChunkStorage());
            } catch (ConcurrentModificationException exception) {
                if (attempt >= 5)
                    throw exception;
            }
        }
    }

    /**
     * The chunks covered by an island's border. The border is the island's
     * {@link UpgradeType#WORLDBORDER_SIZE} value, centred on its fixed centre, plus one ring.
     */
    private BorderBox borderBox(Island island) {
        double size = this.plugin.upgrades()
                .value(island, UpgradeType.WORLDBORDER_SIZE, this.plugin.configuration().defaultWorldBorderSize());
        int radius = (int) Math.ceil(size / 2 / 16) + 1;
        if (radius > MAX_CHUNK_RADIUS) {
            this.plugin.getSLF4JLogger().warn("Island {} has a {}-block border, wider than the {} blocks scanned; the rest is ignored",
                    island.uniqueId(), (int) size, MAX_CHUNK_RADIUS * 32);
            radius = MAX_CHUNK_RADIUS;
        }
        return new BorderBox((int) Math.floor(island.centerX()) >> 4, (int) Math.floor(island.centerZ()) >> 4, radius);
    }

    /**
     * Stores a scan's result. Only the two columns the scan owns are written, and applied to the
     * island currently cached: the {@code island} a scan started from can be seconds old, and saving
     * the whole object would revert anything renamed, locked or moved meanwhile.
     */
    private CompletableFuture<Void> persist(Island island, long value) {
        long level = value / Math.max(1, this.plugin.configuration().level().pointsPerLevel());
        if (island.value() == value && island.level() == level)
            return CompletableFuture.completedFuture(null); // nothing changed: no write, no network-wide refresh

        long previousLevel = island.level();
        return this.plugin.islands()
                .repository()
                .updateColumns(island.uniqueId(), Map.of("value", value, "level", level), cached -> {
                    cached.value(value);
                    cached.level(level);
                })
                .thenRun(() -> new IslandLevelChangedEvent(island, previousLevel, level, value).callEvent());
    }

    /**
     * Rescans the islands hosted on this server whose blocks changed, strictly one after another.
     * Each rescan only reads the changed chunks, unless the island has had no full scan since its
     * world loaded.
     *
     * <p>A pass that outlives the timer interval is skipped rather than doubled up, so two chains
     * never walk the island list side by side.
     */
    private void rescanHostedIslands() {
        if (!this.rescanning.compareAndSet(false, true)) {
            // A pass that has outlived several intervals is not running any more, it is stuck: its
            // continuation was lost (a disable mid-pass, say). Take it over rather than leaving the
            // server without rescans until it restarts.
            long running = System.currentTimeMillis() - this.rescanStartedAt;
            long stuckAfter = this.plugin.configuration().level().rescanIntervalSeconds() * 1000L * STUCK_PASS_INTERVALS;
            if (running < stuckAfter) {
                this.plugin.getSLF4JLogger().debug("Skipping the island rescan: the previous pass is still running.");
                return;
            }
            this.plugin.getSLF4JLogger().warn("The island rescan started {}s ago never finished; starting a new pass.", running / 1000);
        }

        // Bumping the generation retires whatever the previous pass was doing: if it was merely slow
        // rather than stuck, its next continuation sees a stale generation and stops.
        int generation = this.rescanGeneration.incrementAndGet();
        this.rescanStartedAt = System.currentTimeMillis();
        List<UUID> changed = this.plugin.worlds().getLoadedWorlds().keySet().stream()
                .filter(this::isDirty)
                .toList();
        rescanNext(changed, 0, generation);
    }

    /**
     * Scans the first island at or after {@code index} that is still cached, then continues from the
     * scheduler once it finishes. Islands that cannot be scanned are skipped in a loop rather than by
     * recursing, and the continuation goes through the scheduler, so a run of immediate failures
     * cannot stack up frames on the caller.
     */
    private void rescanNext(List<UUID> islandIds, int index, int generation) {
        if (generation != this.rescanGeneration.get())
            return; // a newer pass took over; this chain is retired and must not touch the latch

        for (int cursor = index; cursor < islandIds.size(); cursor++) {
            UUID islandId = islandIds.get(cursor);
            Island island = this.plugin.islands()
                    .repository()
                    .findCachedById(islandId)
                    .orElse(null);
            if (island == null)
                continue;

            int next = cursor + 1;
            scan(island, false).whenComplete((value, throwable) -> {
                if (throwable != null && !(unwrap(throwable) instanceof ScanInProgressException))
                    this.plugin.getSLF4JLogger().warn("Failed to rescan island {}: {}", islandId, unwrap(throwable).getMessage());

                try {
                    Bukkit.getScheduler().runTask(this.plugin, () -> rescanNext(islandIds, next, generation));
                } catch (Exception exception) {
                    // Scheduling throws once the plugin is disabling; end the pass rather than
                    // leaving the latch held by a chain that will never continue.
                    this.plugin.getSLF4JLogger().warn("Island rescan pass aborted at {}: {}", islandId, exception.getMessage());
                    endPass(generation);
                }
            });
            return;
        }

        endPass(generation);
    }

    /** Releases the pass latch, unless a newer pass has already taken it over. */
    private void endPass(int generation) {
        if (generation == this.rescanGeneration.get())
            this.rescanning.set(false);
    }

    // =========================================================================
    //  Leaderboard
    // =========================================================================

    /** The cached leaderboard, best island first. Refreshed on a timer. */
    @Unmodifiable
    public List<Island> top() {
        return this.top;
    }

    /** Reloads the leaderboard from the database. */
    public CompletableFuture<List<Island>> refreshTop() {
        return this.plugin.islands()
                .repository()
                .findTop(this.plugin.configuration().level().topSize())
                .whenComplete((islands, throwable) -> {
                    if (throwable != null) {
                        this.plugin.getSLF4JLogger().error("Failed to refresh the island leaderboard", throwable);
                        return;
                    }
                    this.top = islands;
                });
    }

    // =========================================================================
    //  Cooldown
    // =========================================================================

    /** Milliseconds left before {@code islandId} may be rescanned on demand; {@code 0} when ready. */
    public long cooldownRemaining(UUID islandId) {
        long cooldown = this.plugin.configuration().level().cooldownSeconds() * 1000L;
        if (cooldown <= 0)
            return 0;
        Long last = this.lastScan.get(islandId);
        if (last == null)
            return 0;
        return Math.max(0, cooldown - (System.currentTimeMillis() - last));
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    // =========================================================================
    //  Internals
    // =========================================================================

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    /** The square of chunks a scan covers around an island's centre. */
    record BorderBox(int centerX, int centerZ, int radius) {
        boolean contains(long chunk) {
            return Math.abs(chunkX(chunk) - this.centerX) <= this.radius
                   && Math.abs(chunkZ(chunk) - this.centerZ) <= this.radius;
        }
    }

    /** State of one island scan as it walks its chunks batch by batch. */
    private static final class Scan {
        private final UUID islandId;
        private final World world;
        private final IslandScore score;
        private final List<Long> chunks;
        private final boolean full;
        /** Value of each chunk read so far. */
        private final Map<Long, Long> values = new HashMap<>();
        /** Chunks captured so far, handed back as dirty if the scan fails. */
        private final Set<Long> captured = new HashSet<>();
        /** Hoppers of each chunk read so far, in case the island's hoppers were never counted. */
        private final Map<Long, Integer> hoppers;
        private final long startedAt = System.nanoTime();
        private int cursor;

        private Scan(UUID islandId, World world, IslandScore score, List<Long> chunks, boolean full,
                     Map<Long, Integer> hoppers) {
            this.islandId = islandId;
            this.world = world;
            this.score = score;
            this.chunks = chunks;
            this.full = full;
            this.hoppers = hoppers;
        }
    }

    /** One chunk as captured on the main thread, summed later off it. */
    private record Captured(long chunk, @Nullable ChunkSnapshot snapshot, long spawnerValue) {
        long value(World world, Map<Material, Integer> materialValues) {
            return this.spawnerValue + (this.snapshot == null ? 0 : sum(this.snapshot, world, materialValues));
        }
    }

    /** The island's world is not loaded on this server, so its blocks cannot be read here. */
    public static class NotHostedException extends IllegalStateException {
        public NotHostedException(UUID islandId) {
            super("Island world is not loaded on this server: " + islandId);
        }
    }

    /** A scan is already running for this island. */
    public static class ScanInProgressException extends IllegalStateException {
        public ScanInProgressException(UUID islandId) {
            super("A scan is already running for island: " + islandId);
        }
    }
}
