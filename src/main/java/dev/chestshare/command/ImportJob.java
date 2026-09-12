package dev.chestshare.command;

import dev.chestshare.ChestShare;
import dev.chestshare.ContainerRegistrar;
import dev.chestshare.SharedMarker;
import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.mixin.ServerChunkManagerAccessor;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Async/ticket-based container import, ported from 0.2.3 and extended for modded/compat
 * containers. Runs entirely off the server tick, never blocking on a synchronous chunk
 * load. See NOTES.md for the full design rationale.
 */
public final class ImportJob {
    public static final int MIN_IN_FLIGHT_CHUNKS = 1;
    public static final int MAX_IN_FLIGHT_CHUNKS = 8;
    public static final int DEFAULT_IN_FLIGHT_CHUNKS = 1;
    private static final int PROGRESS_INTERVAL = 500;
    private static final int CHUNK_LOAD_TIMEOUT_TICKS = 600;
    private static final long TICK_BUDGET_NANOS = 5_000_000L;
    private static final int MAX_RESIDENT_CHUNKS = 384;
    private static final double MIN_FREE_HEAP_RATIO = 0.2;
    private static final long PAUSE_LOG_INTERVAL_MS = 30_000L;
    // Sentinel for "not resolved yet" vs "resolved to null" - see NOTES.md
    private static final ChunkResult<LevelChunk> PENDING = ChunkResult.error("chestshare$pending");
    private static final TicketType<ChunkPos> TICKET = TicketType.UNKNOWN;

    public static volatile ImportJob ACTIVE;

    private final Deque<ChunkWork> queue;
    private final int maxInFlightChunks;
    private final List<InFlight> inFlight;
    private final boolean force;
    private final CommandSourceStack source;
    private final int total;
    private int processed;
    private int converted;
    private int skippedOccupied;
    private int skippedMissing;
    private int skippedShared;
    private final Map<ServerLevel, Integer> baselineLoaded = new IdentityHashMap<>();
    private long lastPauseLogMs;

    static final Comparator<ChunkWork> SPATIAL_ORDER = (a, b) ->
            compareSpatial(a.chunkPos().x, a.chunkPos().z, b.chunkPos().x, b.chunkPos().z);

    public ImportJob(Deque<ChunkWork> queue, boolean force, int maxInFlightChunks,
                      int preSkippedShared, int preSkippedMissing, CommandSourceStack source) {
        this.queue = spatiallyOrdered(queue);
        this.force = force;
        this.maxInFlightChunks = Math.max(MIN_IN_FLIGHT_CHUNKS, Math.min(MAX_IN_FLIGHT_CHUNKS, maxInFlightChunks));
        this.inFlight = new ArrayList<>(this.maxInFlightChunks);
        this.source = source;
        int positions = 0;
        for (ChunkWork work : queue) positions += work.positions().size();
        // preSkippedMissing folding - see NOTES.md
        this.total = positions + preSkippedShared + preSkippedMissing;
        this.skippedShared = preSkippedShared;
        this.skippedMissing = preSkippedMissing;
        this.processed = preSkippedShared + preSkippedMissing;
    }

    public int total() { return this.total; }
    public int processed() { return this.processed; }
    public int converted() { return this.converted; }
    public int inFlightCount() { return this.inFlight.size(); }
    public int parallelism() { return this.maxInFlightChunks; }

    public static void tickActive(MinecraftServer server) {
        ImportJob job = ACTIVE;
        if (job != null) job.tick(server);
    }

    private void tick(MinecraftServer server) {
        HolderLookup.Provider registries = server.registryAccess();
        long now = server.overworld().getGameTime();
        long tickStartNanos = System.nanoTime();

        for (int i = this.inFlight.size() - 1; i >= 0 && System.nanoTime() - tickStartNanos < TICK_BUDGET_NANOS; i--) {
            InFlight flight = this.inFlight.get(i);
            ChunkWork work = flight.work;
            LevelChunk chunk = pollChunk(work);
            if (chunk != null) {
                processChunk(work, chunk, registries);
                releaseTicket(work);
                this.inFlight.remove(i);
            } else if (now - flight.requestedTick >= CHUNK_LOAD_TIMEOUT_TICKS) {
                countMissing(work.positions().size());
                releaseTicket(work);
                this.inFlight.remove(i);
            }
        }

        while (this.inFlight.size() < this.maxInFlightChunks && !this.queue.isEmpty()) {
            ChunkWork next = this.queue.peek();
            if (admissionPaused(next.world())) break;
            ChunkWork work = this.queue.poll();
            requestTicket(work);
            this.inFlight.add(new InFlight(work, now));
        }

        if (this.queue.isEmpty() && this.inFlight.isEmpty()) {
            finish(server);
        }
    }

    private boolean admissionPaused(ServerLevel world) {
        int baseline = this.baselineLoaded.computeIfAbsent(world, w -> w.getChunkSource().getLoadedChunksCount());
        int current = world.getChunkSource().getLoadedChunksCount();
        Runtime rt = Runtime.getRuntime();
        long maxHeap = rt.maxMemory();
        long freeHeap = maxHeap - (rt.totalMemory() - rt.freeMemory());
        if (!shouldPauseAdmission(freeHeap, maxHeap, current, baseline)) return false;
        long nowMs = System.currentTimeMillis();
        if (nowMs - this.lastPauseLogMs >= PAUSE_LOG_INTERVAL_MS) {
            this.lastPauseLogMs = nowMs;
            boolean heapGate = (double) freeHeap < MIN_FREE_HEAP_RATIO * (double) maxHeap;
            if (heapGate) {
                ChestShare.LOGGER.info("Import: pausing chunk admission (HEAP gate: {} MiB free of {} MiB, < {}%), letting generation/unload catch up",
                        freeHeap >> 20, maxHeap >> 20, (int) (MIN_FREE_HEAP_RATIO * 100));
            } else {
                ChestShare.LOGGER.info("Import: pausing chunk admission (CHUNKS gate: {} chunks above baseline), letting unload catch up",
                        current - baseline);
            }
        }
        return true;
    }

    static boolean shouldPauseAdmission(long freeHeapBytes, long maxHeapBytes, int loadedNow, int baseline) {
        boolean heapLow = (double) freeHeapBytes < MIN_FREE_HEAP_RATIO * (double) maxHeapBytes;
        boolean tooManyResident = loadedNow - baseline > MAX_RESIDENT_CHUNKS;
        return heapLow || tooManyResident;
    }

    static int compareSpatial(int ax, int az, int bx, int bz) {
        int c = Integer.compare(ax >> 5, bx >> 5);
        if (c != 0) return c;
        c = Integer.compare(az >> 5, bz >> 5);
        if (c != 0) return c;
        c = Integer.compare(ax, bx);
        return c != 0 ? c : Integer.compare(az, bz);
    }

    private static Deque<ChunkWork> spatiallyOrdered(Deque<ChunkWork> queue) {
        Map<ServerLevel, List<ChunkWork>> byWorld = new LinkedHashMap<>();
        for (ChunkWork work : queue) byWorld.computeIfAbsent(work.world(), w -> new ArrayList<>()).add(work);
        Deque<ChunkWork> ordered = new ArrayDeque<>(queue.size());
        for (List<ChunkWork> worldWork : byWorld.values()) {
            worldWork.sort(SPATIAL_ORDER);
            ordered.addAll(worldWork);
        }
        return ordered;
    }

    private void requestTicket(ChunkWork work) {
        work.world().getChunkSource().addRegionTicket(TICKET, work.chunkPos(), 0, work.chunkPos());
    }

    private void releaseTicket(ChunkWork work) {
        work.world().getChunkSource().removeRegionTicket(TICKET, work.chunkPos(), 0, work.chunkPos());
    }

    private LevelChunk pollChunk(ChunkWork work) {
        ChunkHolder holder = ((ServerChunkManagerAccessor) work.world().getChunkSource())
                .chestshare$getChunkHolder(work.chunkPos().toLong());
        if (holder == null) return null;
        // Must use getFullChunkFuture() (FULL status), not getTickingChunkFuture() - see NOTES.md
        CompletableFuture<ChunkResult<LevelChunk>> future = holder.getFullChunkFuture();
        ChunkResult<LevelChunk> result = future.getNow(PENDING);
        return result == PENDING ? null : result.orElse(null);
    }

    private void processChunk(ChunkWork work, LevelChunk chunk, HolderLookup.Provider registries) {
        List<PendingPos> positions = work.positions();
        ServerLevel world = work.world();
        SharedContainersState state = SharedContainersState.get(world);
        int done = 0;
        try {
            for (PendingPos entry : positions) {
                // chunk.getBlockEntity() is already correct here - no getChunkAt()
                // fallback needed, see NOTES.md
                BlockEntity be = chunk.getBlockEntity(entry.pos());
                if (be instanceof RandomizableContainerBlockEntity container) {
                    if (((SharedMarker) container).chestshare$isShared()) {
                        this.skippedShared++;
                    } else if (!this.force && !container.isEmpty()) {
                        this.skippedOccupied++;
                    } else {
                        ContainerRegistrar.applyTemplate(world, container,
                                ContainerTemplate.fromNbt(entry.template(), registries));
                        this.converted++;
                    }
                } else if (be != null && ContainerCompatibility.isSupportedContainer(be)) {
                    if (be instanceof SharedMarker marker && marker.chestshare$isShared()) {
                        this.skippedShared++;
                    } else {
                        ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
                        boolean empty = true;
                        for (int s = 0; s < inv.size(); s++) {
                            if (!inv.get(s).isEmpty()) { empty = false; break; }
                        }
                        if (!this.force && !empty) {
                            this.skippedOccupied++;
                        } else {
                            ContainerTemplate template = ContainerTemplate.fromNbt(entry.template(), registries);
                            for (int s = 0; s < inv.size(); s++) {
                                ItemStack fill = template instanceof ContainerTemplate.ItemListTemplate il && s < il.items().size()
                                        ? il.items().get(s).copy() : ItemStack.EMPTY;
                                inv.set(s, fill);
                            }
                            if (be instanceof SharedMarker marker) marker.chestshare$setShared(true);
                            be.setChanged();
                            state.putBlock(entry.pos(), new SharedContainerEntry(template));
                            this.converted++;
                        }
                    }
                } else {
                    this.skippedMissing++;
                }
                done++;
                advanceProgress();
            }
        } catch (Exception e) {
            ChestShare.LOGGER.warn("Import: failed to process chunk {} in {}, counting its remaining positions as missing",
                    work.chunkPos(), work.world().dimension().location(), e);
            countMissing(positions.size() - done);
        }
    }

    private void countMissing(int n) {
        for (int i = 0; i < n; i++) {
            this.skippedMissing++;
            advanceProgress();
        }
    }

    private void advanceProgress() {
        this.processed++;
        if (this.processed % PROGRESS_INTERVAL == 0) {
            ChestShare.LOGGER.info("Import progress: {}/{} positions processed", this.processed, this.total);
        }
    }

    private void finish(MinecraftServer server) {
        String summary = "Imported " + this.converted + " shared containers (skipped: " + this.skippedOccupied + " not empty"
                + (this.skippedOccupied > 0 && !this.force ? " — rerun with 'force' to convert them" : "")
                + ", " + this.skippedMissing + " missing, " + this.skippedShared + " already shared)";
        ChestShare.LOGGER.info(summary);
        if (this.source != null) {
            try {
                this.source.sendSuccess(() -> Component.literal(summary), true);
            } catch (Exception e) {
                ChestShare.LOGGER.debug("Could not send import summary to command source: {}", e.getMessage());
            }
        }
        ACTIVE = null;
        ChestShareCommands.refreshCommandTrees(server);
    }

    public void cancel(MinecraftServer server) {
        for (InFlight flight : this.inFlight) releaseTicket(flight.work);
        this.inFlight.clear();
        String summary = "Import cancelled: " + this.processed + "/" + this.total + " positions processed (converted "
                + this.converted + ", skipped: " + this.skippedOccupied + " not empty, " + this.skippedMissing
                + " missing, " + this.skippedShared + " already shared)";
        ChestShare.LOGGER.info(summary);
        if (this.source != null) {
            try {
                this.source.sendSuccess(() -> Component.literal(summary), true);
            } catch (Exception e) {
                ChestShare.LOGGER.debug("Could not send import cancellation summary to command source: {}", e.getMessage());
            }
        }
        ACTIVE = null;
        ChestShareCommands.refreshCommandTrees(server);
    }

    public static ServerLevel resolveWorld(MinecraftServer server, String dimensionId) {
        for (ServerLevel w : server.getAllLevels()) {
            if (w.dimension().location().toString().equals(dimensionId)) return w;
        }
        return null;
    }

    public record PendingPos(BlockPos pos, CompoundTag template) {}
    public record ChunkWork(ServerLevel world, ChunkPos chunkPos, List<PendingPos> positions) {}

    private static final class InFlight {
        final ChunkWork work;
        final long requestedTick;
        InFlight(ChunkWork work, long requestedTick) { this.work = work; this.requestedTick = requestedTick; }
    }
}
