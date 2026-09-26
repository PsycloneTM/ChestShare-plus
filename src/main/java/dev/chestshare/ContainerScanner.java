package dev.chestshare;

import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.compat.FingerprintRegistry;
import dev.chestshare.open.SharedContainerOpener;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
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
        SharedContainersState sharedState = SharedContainersState.get(world);

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            boolean markerShared = be instanceof SharedMarker marker && marker.chestshare$isShared();
            boolean stateShared = sharedState.getBlock(be.getBlockPos()) != null;

            // Already registered: the passive scan never touches it - no rebuild, no marker
            // re-sync, no provenance stamping. See NOTES.md.
            if (stateShared) continue;
            if (markerShared) continue;

            boolean restoreTried = false;

            if (!be.getClass().getName().startsWith("net.minecraft.")
                    && ContainerCompatibility.isSupportedContainer(be)) {
                if (freshlyGenerated) {
                    SharedContainerEntry registered = ContainerCompatibility.register(world, be, false);
                    if (registered != null) {
                        changed = true;
                        continue;
                    }
                } else if (FingerprintRegistry.isBuilt()) {
                    ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
                    if (inv != null && FingerprintRegistry.matchesKnownFingerprint(inv)) {
                        var start = world.structureManager().getStructureWithPieceAt(
                                be.getBlockPos(), h -> true);
                        if (start != null && start.isValid()) {
                            SharedContainerEntry registered = ContainerCompatibility.register(world, be, false);
                            if (registered != null) {
                                changed = true;
                                continue;
                            }
                        }
                    }

                    restoreTried = true;
                    if (StructureContainerRestorer.tryRestoreEmpty(world, be)) {
                        changed = true;
                        continue;
                    }
                }
            }

            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                if (randomizable.getLootTable() != null) {
                    changed |= ContainerRegistrar.register(world, randomizable, false) != null;
                } else if (!freshlyGenerated && !restoreTried
                        && StructureContainerRestorer.tryRestoreEmpty(world, be)) {
                    changed = true;
                }
                continue;
            }

            if (freshlyGenerated && be instanceof Container container
                    && be instanceof MenuProvider
                    && !be.getClass().getName().startsWith("net.minecraft.")) {
                if (!container.isEmpty()) {
                    changed |= SharedContainerOpener.scanRegisterGenericContainer(world, be, container) != null;
                }
            }
        }

        if (changed) chunk.setUnsaved(true);
    }
}
