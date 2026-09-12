package dev.chestshare;

import dev.chestshare.command.ChestShareCommands;
import dev.chestshare.command.ImportJob;
import dev.chestshare.compat.ContainerCompatibility;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import dev.chestshare.open.SharedContainerOpener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChestShare implements ModInitializer {
    public static final String MOD_ID = "chestshare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Queue<ScanRequest> PENDING_SCANS = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_COUNT = new java.util.concurrent.atomic.AtomicInteger();
    // See NOTES.md: FRESHLY_GENERATED_CHUNKS
    private static final java.util.Set<Long> FRESHLY_GENERATED_CHUNKS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private record ScanRequest(net.minecraft.server.level.ServerLevel world, LevelChunk chunk) {}

    @Override
    public void onInitialize() {
        // See NOTES.md: CHUNK_GENERATE registration
        ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) -> {
            FRESHLY_GENERATED_CHUNKS.add(chunk.getPos().toLong());
        });

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (world instanceof net.minecraft.server.level.ServerLevel serverWorld) {
                PENDING_SCANS.add(new ScanRequest(serverWorld, chunk));
                PENDING_COUNT.incrementAndGet();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int backlog = PENDING_COUNT.get();
            int budget = Math.max(4, Math.min(backlog, 200));
            int processed = 0;
            ScanRequest request;
            while (processed < budget && (request = PENDING_SCANS.poll()) != null) {
                PENDING_COUNT.decrementAndGet();
                try {
                    LevelChunk loaded = request.world().getChunkSource().getChunkNow(
                            request.chunk().getPos().x, request.chunk().getPos().z);
                    if (loaded == request.chunk()) {
                        boolean freshlyGenerated = FRESHLY_GENERATED_CHUNKS.remove(request.chunk().getPos().toLong());
                        ContainerScanner.scanChunk(request.world(), request.chunk(), freshlyGenerated);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Failed to scan loaded chunk {} for ChestShare", request.chunk().getPos(), t);
                }
                processed++;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(ImportJob::tickActive);

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerLevel sw) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            var pos = hit.getBlockPos();
            BlockEntity be = sw.getBlockEntity(pos);
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                SharedContainerOpener.resolveBlockEntry(sw, randomizable);
                if (sw.getBlockState(pos).getBlock() instanceof ChestBlock
                        && sw.getBlockState(pos).getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    var otherPos = pos.relative(ChestBlock.getConnectedDirection(sw.getBlockState(pos)));
                    if (sw.getBlockEntity(otherPos) instanceof ChestBlockEntity other) {
                        SharedContainerOpener.resolveBlockEntry(sw, other);
                    }
                }
            } else if (be instanceof Container container
                    && be instanceof MenuProvider
                    && be instanceof SharedMarker marker
                    && !be.getClass().getName().startsWith("net.minecraft.")) {
                SharedContainerOpener.resolveGenericEntryForUse(sw, be, container);
            } else if (be != null && !be.getClass().getName().startsWith("net.minecraft.")
                    && ContainerCompatibility.isSupportedContainer(be)) {
                // See NOTES.md: reflective compat branch in UseBlockCallback
                if (SharedContainerOpener.openCompatContainerForUse(sp, sw, be)) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel sw) || !(player instanceof ServerPlayer sp)) return;
            SharedContainersState s = SharedContainersState.get(sw);
            SharedContainerEntry e = s.getBlock(pos);
            if (e == null) return;
            s.removeBlock(pos);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ChestShareCommands.register(dispatcher));
        LOGGER.info("ChestShare initialized (lazy container discovery enabled)");
    }
}
