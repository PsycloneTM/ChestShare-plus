package dev.chestshare;

import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.open.SharedContainerOpener;
import dev.chestshare.state.SharedContainerEntry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

// Scans only loaded chunks, never the world-generation callback. See NOTES.md.
public final class ContainerScanner {
    private ContainerScanner() {}

    // freshlyGenerated: true only for a chunk just created by world-gen. See NOTES.md
    // for why every modded/generic branch below is gated on it.
    public static void scanChunk(ServerLevel world, LevelChunk chunk, boolean freshlyGenerated) {
        boolean changed = false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof SharedMarker marker && marker.chestshare$isShared()) continue;

            if (freshlyGenerated && !be.getClass().getName().startsWith("net.minecraft.")) {
                if (ContainerCompatibility.isSupportedContainer(be)) {
                    SharedContainerEntry registered = ContainerCompatibility.register(world, be, false);
                    if (registered != null) {
                        changed = true;
                        continue;
                    }
                }
            }

            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                if (randomizable.getLootTable() != null) {
                    changed |= ContainerRegistrar.register(world, randomizable, false) != null;
                }
                continue;
            }

            if (freshlyGenerated && be instanceof Container container
                    && be instanceof MenuProvider
                    && !be.getClass().getName().startsWith("net.minecraft.")) {
                if (!container.isEmpty()) {
                    changed |= SharedContainerOpener.resolveGenericEntryForUse(world, be, container) != null;
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }
}
