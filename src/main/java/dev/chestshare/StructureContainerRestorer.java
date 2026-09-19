package dev.chestshare;

import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.compat.FingerprintRegistry;
import dev.chestshare.compat.StructurePlacement;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

/** The passive counterpart of {@code /chestshare adopt-structure}, for the one case where doing it
 *  without an admin is defensible: an EMPTY container standing at exactly the position, and being
 *  exactly the block, that a registered structure template places a storage block. It is refilled
 *  from the template (baked items or loot table) and registered as a shared container.
 *
 *  Called from {@link ContainerScanner} for chunks that already existed (a freshly generated chunk
 *  registers everything through its own branches). Off unless
 *  {@link ChestShareConfig#restoreEmptyStructureContainers()} is true. See NOTES.md.
 *
 *  Every check below has to pass; failing any of them just leaves the container alone:
 *  <ul>
 *    <li>the container is unshared, holds nothing, and has no loot table left (a container that
 *        still has one is handled by the ordinary loot-table branch);</li>
 *    <li>it is a chest, barrel, shulker box or supported modded container - never a hopper,
 *        dispenser or furnace;</li>
 *    <li>a structure piece covers it, that piece is a jigsaw piece whose template id the game
 *        reports outright, and the template has recorded storage blocks. The layout-matching and
 *        content-alignment guesses adopt-structure falls back on are NOT used here;</li>
 *    <li>the template block, moved through that piece's rotation and origin, lands on this exact
 *        position, and exactly one piece/block claims it;</li>
 *    <li>the template block is the same block that is standing here now, carries either baked
 *        items or a loot table, and comes from a single-palette template.</li>
 *  </ul>
 *  Only this container's own block entity, the chunk's structure references and the structure
 *  start are read - never a block entity in another chunk, so the scan can't force chunk loads
 *  by looking at the rest of a piece that spills across a chunk border. */
public final class StructureContainerRestorer {
    private StructureContainerRestorer() {}

    private record Claim(ResourceLocation templateId, FingerprintRegistry.StructureStorageBlock block) {}

    /** @return true if the container was refilled and registered as shared. */
    public static boolean tryRestoreEmpty(ServerLevel world, BlockEntity be) {
        if (!ChestShareConfig.restoreEmptyStructureContainers()) return false;
        if (!FingerprintRegistry.isBuilt()) return false;
        if (!(be instanceof SharedMarker marker) || marker.chestshare$isShared()) return false;
        if (!isRestorableType(be) || !isEmptyWithoutLootTable(be)) return false;

        BlockPos pos = be.getBlockPos();
        StructureStart start = world.structureManager().getStructureWithPieceAt(pos, h -> true);
        if (start == null || !start.isValid()) return false;

        List<Claim> claims = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece poolPiece)) continue;
            if (!piece.getBoundingBox().isInside(pos)) continue;
            ResourceLocation templateId = StructurePlacement.resolveTemplateId(poolPiece);
            if (templateId == null) continue;
            List<FingerprintRegistry.StructureStorageBlock> blocks =
                    FingerprintRegistry.getStructureStorageBlocks(templateId);
            if (blocks == null) continue;
            Rotation rotation = poolPiece.getRotation();
            BlockPos origin = poolPiece.getPosition();
            for (FingerprintRegistry.StructureStorageBlock block : blocks) {
                // Same placement transform adopt-structure uses, so both agree on where a
                // template block lands in the world.
                BlockPos worldPos = StructureTemplate.transform(
                        new BlockPos(block.x(), block.y(), block.z()), Mirror.NONE, rotation, BlockPos.ZERO)
                        .offset(origin);
                if (worldPos.equals(pos)) claims.add(new Claim(templateId, block));
            }
        }
        // Two pieces (or two blocks) both claiming this position means the placement isn't
        // understood well enough to act on. adopt-structure can be told to look; the scan can't.
        if (claims.size() != 1) return false;

        Claim claim = claims.get(0);
        FingerprintRegistry.StructureStorageBlock block = claim.block();
        if (block.multiPalette() || block.blockId() == null || block.emptyInTemplate()) return false;
        String liveBlockId = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        if (!block.blockId().equals(liveBlockId)) return false;

        SharedContainerEntry entry;
        String restoredFrom;
        if (block.hasLootTable()) {
            ResourceLocation tableId = ResourceLocation.tryParse(block.lootTable());
            if (tableId == null) return false;
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                entry = ContainerRegistrar.applyTemplate(world, randomizable,
                        new ContainerTemplate.LootTableTemplate(tableId, block.lootTableSeed(),
                                randomizable.getContainerSize()), false);
            } else {
                entry = ContainerCompatibility.registerWithLootTable(world, be, tableId,
                        block.lootTableSeed(), false);
            }
            restoredFrom = "loot table " + tableId;
        } else {
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                int size = randomizable.getContainerSize();
                entry = ContainerRegistrar.applyTemplate(world, randomizable,
                        new ContainerTemplate.ItemListTemplate(
                                ContainerCompatibility.toStacks(block.items(), size), size), false);
            } else {
                entry = ContainerCompatibility.registerWithItems(world, be, block.items(), false);
            }
            restoredFrom = block.items().size() + " baked item(s)";
        }
        if (entry == null) return false;

        ChestShare.LOGGER.info("[ChestShare] restored empty structure container '{}' at {} in {} from {} ({})",
                be.getClass().getSimpleName(), pos, world.dimension().location(), claim.templateId(), restoredFrom);
        return true;
    }

    /** The passive path's own, deliberately narrow, idea of what it may take over. Mirrors
     *  adopt-structure's notion of an adoptable container minus its "carries a loot table" clause
     *  (which can't apply to a container we already know has none). */
    private static boolean isRestorableType(BlockEntity be) {
        if (ContainerCompatibility.isSupportedContainer(be)) return true;
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity;
    }

    private static boolean isEmptyWithoutLootTable(BlockEntity be) {
        if (be instanceof RandomizableContainerBlockEntity randomizable) {
            // getLootTable() first, and it must short-circuit: RandomizableContainerBlockEntity
            // #isEmpty() unpacks a pending loot table, which would roll and destroy the very
            // template this container is registered from.
            return randomizable.getLootTable() == null && randomizable.isEmpty();
        }
        ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
        if (inv == null || inv.size() <= 0) return false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack != null && !stack.isEmpty()) return false;
        }
        return true;
    }
}
