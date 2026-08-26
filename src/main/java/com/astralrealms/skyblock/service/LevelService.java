package com.astralrealms.skyblock.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.CreatureSpawner;
import org.jetbrains.annotations.Unmodifiable;

import com.astralrealms.skyblock.AstralSkyblock;
import com.astralrealms.skyblock.model.island.Island;
import com.astralrealms.skyblock.model.upgrade.UpgradeType;

/**
 * Scores islands: sums the value of every block inside an island's border (from
 * {@code block-values.yml}) into the island's {@code value}, and derives its {@code level} from
 * the configured points per level.
 *
 * <p>Scanning is deliberately incremental. Chunks are pulled a batch at a time, each batch snapshots
 * on the main thread and is summed off it, and the next batch is scheduled a tick later — a large
 * island therefore costs a slice of several ticks instead of a freeze. Only the server hosting the
 * island world can scan it, which is also the server that runs the periodic rescan.
 */
public class LevelService {

    /** Hard ceiling on the scanned radius, so a misconfigured border cannot scan the whole world. */
    private static final int MAX_CHUNK_RADIUS = 64;
    /** Rescan intervals a pass may span before the timer assumes it is stuck and takes the latch back. */
    private static final int STUCK_PASS_INTERVALS = 4;

    private final AstralSkyblock plugin;
    // Islands with a scan in flight, so a second /is calc cannot start a parallel scan.
    private final Set<UUID> scanning = ConcurrentHashMap.newKeySet();
    // islandId -> epoch millis of its last completed scan, for the /is calc cooldown.
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();
    private volatile List<Island> top = List.of();
    // Whether a rescan pass is walking the hosted islands, so the timer cannot start a second one.
    private final AtomicBoolean rescanning = new AtomicBoolean();
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

    /**
     * Rescans an island and persists its new value and level. Fails when the island world is not
     * loaded on this server (only its host can read its blocks) or when a scan is already running
     * for it.
     */
    public CompletableFuture<Long> calculate(Island island) {
        World world = this.plugin.worlds()
                .findByIslandId(island.uniqueId())
                .map(instance -> instance.getBukkitWorld())
                .orElse(null);
        if (world == null)
            return CompletableFuture.failedFuture(new NotHostedException(island.uniqueId()));
        if (!this.scanning.add(island.uniqueId()))
            return CompletableFuture.failedFuture(new ScanInProgressException(island.uniqueId()));

        CompletableFuture<Long> result = new CompletableFuture<>();
        Scan scan = new Scan(island.uniqueId(), world, chunkCoordinates(island, world));
        Bukkit.getScheduler().runTask(this.plugin, () -> processBatch(scan, result));

        return result
                .thenCompose(value -> persist(island, value).thenApply(ignored -> value))
                .whenComplete((value, throwable) -> {
                    this.scanning.remove(island.uniqueId());
                    if (throwable == null)
                        this.lastScan.put(island.uniqueId(), System.currentTimeMillis());
                });
    }

    /**
     * Processes the next batch of chunks and schedules itself for the following tick until the scan
     * runs out of chunks. Runs on the main thread; only the block summing happens off it.
     */
    private void processBatch(Scan scan, CompletableFuture<Long> result) {
        // The idle sweep (or a delete) can unload the world under a running scan; abort rather than
        // read blocks out of a world that is no longer there. The island simply keeps its old score.
        if (Bukkit.getWorld(scan.world.getName()) == null) {
            result.completeExceptionally(new NotHostedException(scan.islandId));
            return;
        }

        if (scan.cursor >= scan.chunks.size()) {
            result.complete(scan.value);
            return;
        }

        int batchSize = this.plugin.configuration().level().chunksPerBatch();
        int end = Math.min(scan.cursor + batchSize, scan.chunks.size());
        List<CompletableFuture<ChunkSnapshot>> snapshots = new ArrayList<>();
        for (int index = scan.cursor; index < end; index++) {
            long coordinate = scan.chunks.get(index);
            int chunkX = (int) (coordinate >> 32);
            int chunkZ = (int) coordinate;
            // gen = false: an island's unexplored chunks hold nothing and must not be generated.
            // Paper completes this future on the main thread, which is where a snapshot must be taken.
            snapshots.add(scan.world.getChunkAtAsync(chunkX, chunkZ, false)
                    .thenApply(chunk -> chunk == null ? null : snapshot(chunk))
                    .exceptionally(throwable -> {
                        // One unreadable chunk must not void the whole scan.
                        this.plugin.getSLF4JLogger().warn("Skipped chunk {},{} while scanning island {}",
                                chunkX, chunkZ, scan.islandId, throwable);
                        return null;
                    }));
        }
        scan.cursor = end;

        CompletableFuture.allOf(snapshots.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    long batchValue = 0;
                    List<int[]> spawners = new ArrayList<>();
                    for (CompletableFuture<ChunkSnapshot> future : snapshots) {
                        ChunkSnapshot chunkSnapshot = future.join();
                        if (chunkSnapshot != null)
                            batchValue += sum(chunkSnapshot, scan.world, spawners);
                    }
                    scan.spawners.addAll(spawners);
                    return batchValue;
                })
                .whenComplete((batchValue, throwable) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (throwable != null) {
                        result.completeExceptionally(throwable);
                        return;
                    }

                    // Anything thrown here (a world unloaded between batches, say) would otherwise be
                    // swallowed by the scheduler and leave `result` — and every latch waiting on it —
                    // hanging forever, so the scan is failed explicitly instead.
                    try {
                        scan.value += batchValue;
                        // Spawners carry their entity type in a block state the snapshot cannot see,
                        // so the few positions found are resolved here, on the main thread.
                        scan.value += resolveSpawners(scan);
                        processBatch(scan, result);
                    } catch (Exception exception) {
                        result.completeExceptionally(exception);
                    }
                }));
    }

    private ChunkSnapshot snapshot(Chunk chunk) {
        return chunk.getChunkSnapshot(false, false, false);
    }

    /**
     * Sums the plain material values of a chunk, recording the positions of any spawners for the
     * caller to resolve on the main thread. Empty sections are skipped outright.
     */
    private long sum(ChunkSnapshot chunkSnapshot, World world, List<int[]> spawners) {
        Map<Material, Integer> values = this.plugin.blockValuesConfiguration().materialValues();
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
                    if (material == Material.AIR)
                        continue;
                    if (material == Material.SPAWNER) {
                        spawners.add(new int[]{(chunkSnapshot.getX() << 4) + x, y, (chunkSnapshot.getZ() << 4) + z});
                        continue;
                    }

                    Integer value = values.get(material);
                    if (value != null)
                        total += value;
                }
            }
        }
        return total;
    }

    /** Resolves and clears the spawner positions collected by the last batch. */
    private long resolveSpawners(Scan scan) {
        if (scan.spawners.isEmpty())
            return 0;

        Map<org.bukkit.entity.EntityType, Integer> values = this.plugin.blockValuesConfiguration().spawnerValues();
        long total = 0;
        for (int[] position : scan.spawners) {
            if (!(scan.world.getBlockAt(position[0], position[1], position[2]).getState() instanceof CreatureSpawner spawner))
                continue;
            Integer value = spawner.getSpawnedType() == null ? null : values.get(spawner.getSpawnedType());
            if (value != null)
                total += value;
        }
        scan.spawners.clear();
        return total;
    }

    /**
     * The chunks covered by an island's border, as packed {@code (x << 32) | z} coordinates. The
     * border is the island's {@link UpgradeType#WORLDBORDER_SIZE} value, centred on its spawn.
     */
    private List<Long> chunkCoordinates(Island island, World world) {
        double size = this.plugin.upgrades()
                .value(island, UpgradeType.WORLDBORDER_SIZE, this.plugin.configuration().defaultWorldBorderSize());
        int radius = Math.min(MAX_CHUNK_RADIUS, (int) Math.ceil(size / 2 / 16) + 1);

        int centerX = (int) Math.floor(island.spawnX()) >> 4;
        int centerZ = (int) Math.floor(island.spawnZ()) >> 4;

        List<Long> coordinates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++)
            for (int z = centerZ - radius; z <= centerZ + radius; z++)
                coordinates.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        return coordinates;
    }

    private CompletableFuture<Void> persist(Island island, long value) {
        island.value(value);
        island.level(value / Math.max(1, this.plugin.configuration().level().pointsPerLevel()));
        return this.plugin.islands()
                .repository()
                .save(island)
                .thenAccept(ignored -> {
                });
    }

    /**
     * Rescans every island world hosted on this server, strictly one after another: each scan
     * already spends {@code chunks-per-batch} chunk loads and snapshots per tick, so starting one
     * per hosted world at once would multiply that by the number of islands on the server.
     *
     * <p>A pass that outlives the timer interval is skipped rather than doubled up — with the scans
     * serialised, a pass takes roughly {@code islands × scan duration}, and letting the timer start
     * a second chain over the same list would put the per-tick cost right back where it was.
     */
    private void rescanHostedIslands() {
        if (!this.rescanning.compareAndSet(false, true)) {
            // A pass that has outlived several intervals is not running any more, it is stuck: its
            // continuation was lost (a disable mid-pass, say). Take the latch back rather than
            // leaving the server without rescans until it restarts.
            long running = System.currentTimeMillis() - this.rescanStartedAt;
            long stuckAfter = this.plugin.configuration().level().rescanIntervalSeconds() * 1000L * STUCK_PASS_INTERVALS;
            if (running < stuckAfter) {
                this.plugin.getSLF4JLogger().debug("Skipping the island rescan: the previous pass is still running.");
                return;
            }
            this.plugin.getSLF4JLogger().warn("The island rescan started {}s ago never finished; starting a new pass.", running / 1000);
        }

        this.rescanStartedAt = System.currentTimeMillis();
        rescanNext(List.copyOf(this.plugin.worlds().getLoadedWorlds().keySet()), 0);
    }

    /**
     * Scans the first island at or after {@code index} that is still cached, then continues from the
     * scheduler once it finishes. Islands that cannot be scanned are skipped in a loop rather than by
     * recursing, and the continuation goes through the scheduler, so a run of immediate failures
     * cannot stack up frames on the caller.
     */
    private void rescanNext(List<UUID> islandIds, int index) {
        for (int cursor = index; cursor < islandIds.size(); cursor++) {
            UUID islandId = islandIds.get(cursor);
            Island island = this.plugin.islands()
                    .repository()
                    .findCachedById(islandId)
                    .orElse(null);
            if (island == null)
                continue;

            int next = cursor + 1;
            try {
                calculate(island).whenComplete((value, throwable) -> {
                    if (throwable != null && !(unwrap(throwable) instanceof ScanInProgressException))
                        this.plugin.getSLF4JLogger().warn("Failed to rescan island {}: {}", islandId, unwrap(throwable).getMessage());
                    Bukkit.getScheduler().runTask(this.plugin, () -> rescanNext(islandIds, next));
                });
            } catch (Exception exception) {
                // calculate() schedules main-thread work, which throws outright once the plugin is
                // disabling. Release the latch instead of ending the pass while still holding it.
                this.plugin.getSLF4JLogger().warn("Island rescan pass aborted at {}: {}", islandId, exception.getMessage());
                this.rescanning.set(false);
            }
            return;
        }

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

    /** State of one island scan as it walks its chunks batch by batch. */
    private static final class Scan {
        private final UUID islandId;
        private final World world;
        private final List<Long> chunks;
        private final List<int[]> spawners = new ArrayList<>();
        private int cursor;
        private long value;

        private Scan(UUID islandId, World world, List<Long> chunks) {
            this.islandId = islandId;
            this.world = world;
            this.chunks = chunks;
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
