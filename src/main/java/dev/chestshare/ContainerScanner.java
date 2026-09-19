package dev.chestshare;

import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.compat.FingerprintRegistry;
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

            // The empty-container restore below can be reached from two branches for the same
            // block entity (a modded loot container is both "supported compat" and
            // "randomizable"); once is enough, and it costs a structure lookup.
            boolean restoreTried = false;

            if (!be.getClass().getName().startsWith("net.minecraft.")
                    && ContainerCompatibility.isSupportedContainer(be)) {
                if (freshlyGenerated) {
                    // Freshly generated chunk: safe to register any supported compat container
                    // directly (no loot-table signal exists for these; freshlyGenerated is the
                    // only available world-gen origin signal). See NOTES.md.
                    SharedContainerEntry registered = ContainerCompatibility.register(world, be, false);
                    if (registered != null) { changed = true; continue; }
                } else if (FingerprintRegistry.isBuilt()) {
                    // Already-existing chunk: use the fingerprint registry as a secondary capture
                    // path for compat containers in registered structures. The container's current
                    // contents must exactly match a known structure-baked fingerprint, AND the
                    // position must be inside a known registered structure's bounding box - both
                    // conditions required. See compat/NOTES.md for full design rationale and
                    // known limitations (a player building inside a structure's bounding box
                    // with matching item contents cannot be ruled out, but is vanishingly rare).
                    ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
                    if (inv != null && FingerprintRegistry.matchesKnownFingerprint(inv)) {
                        var start = world.structureManager().getStructureWithPieceAt(
                                be.getBlockPos(), h -> true);
                        if (start != null && start.isValid()) {
                            SharedContainerEntry registered = ContainerCompatibility.register(world, be, false);
                            if (registered != null) { changed = true; continue; }
                        }
                    }
                    // Not a fingerprint match. The one other thing worth checking on an existing
                    // chunk is a container that is EMPTY and stands at an exact storage position
                    // of a registered structure template - see StructureContainerRestorer. Off
                    // unless restoreEmptyStructureContainers is enabled in the config.
                    restoreTried = true;
                    if (StructureContainerRestorer.tryRestoreEmpty(world, be)) { changed = true; continue; }
                }
            }

            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                if (randomizable.getLootTable() != null) {
                    changed |= ContainerRegistrar.register(world, randomizable, false) != null;
                } else if (!freshlyGenerated && !restoreTried
                        && StructureContainerRestorer.tryRestoreEmpty(world, be)) {
                    // No loot table left: a vanilla chest/barrel that has already been opened
                    // (or a modded one that got here without going through the compat branch).
                    changed = true;
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
