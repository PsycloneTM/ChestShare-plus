package dev.chestshare;

import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.compat.FingerprintRegistry;
import dev.chestshare.compat.StructurePlacement;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
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

public final class StructureContainerRestorer {
    private StructureContainerRestorer() {}

    private record Claim(ResourceLocation templateId, FingerprintRegistry.StructureStorageBlock block) {}

    public static boolean tryRestoreEmpty(ServerLevel world, BlockEntity be) {
        if (!ChestShareConfig.restoreEmptyStructureContainers()) return false;
        if (!FingerprintRegistry.isBuilt()) return false;
        if (!(be instanceof SharedMarker marker)) return false;
        if (marker.chestshare$isShared()) return false;
        if (!isRestorableType(be)) return false;

        SharedContainersState state = SharedContainersState.get(world);
        if (state.getBlock(be.getBlockPos()) != null) return false;

        Claim claim = findSingleClaim(world, be);
        if (claim == null) return false;
        FingerprintRegistry.StructureStorageBlock block = claim.block();
        if (block.multiPalette() || block.blockId() == null || block.emptyInTemplate()) return false;

        String liveBlockId = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        if (!block.blockId().equals(liveBlockId) || !isEmptyWithoutLootTable(be)) return false;

        ContainerTemplate expectedTemplate = expectedTemplate(be, block);
        if (expectedTemplate == null) return false;
        SharedContainerEntry entry = applyStructureTemplate(world, be, claim, expectedTemplate);
        if (entry == null) return false;

        ChestShare.LOGGER.info("[ChestShare] restored empty structure container '{}' at {} in {} from {} ({})",
                be.getClass().getSimpleName(), be.getBlockPos(), world.dimension().location(),
                claim.templateId(), restoredDescription(block));
        return true;
    }

    private static Claim findSingleClaim(ServerLevel world, BlockEntity be) {
        BlockPos pos = be.getBlockPos();
        StructureStart start = world.structureManager().getStructureWithPieceAt(pos, h -> true);
        if (start == null || !start.isValid()) return null;

        List<Claim> claims = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece poolPiece)) continue;
            if (!piece.getBoundingBox().isInside(pos)) continue;
            ResourceLocation templateId = StructurePlacement.resolveTemplateId(poolPiece);
            if (templateId == null) continue;
            List<FingerprintRegistry.StructureStorageBlock> blocks = FingerprintRegistry.getStructureStorageBlocks(templateId);
            if (blocks == null) continue;
            Rotation rotation = poolPiece.getRotation();
            BlockPos origin = poolPiece.getPosition();
            for (FingerprintRegistry.StructureStorageBlock block : blocks) {
                BlockPos worldPos = StructureTemplate.transform(new BlockPos(block.x(), block.y(), block.z()),
                        Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
                if (worldPos.equals(pos)) claims.add(new Claim(templateId, block));
            }
        }
        return claims.size() == 1 ? claims.get(0) : null;
    }

    private static SharedContainerEntry applyStructureTemplate(ServerLevel world, BlockEntity be,
            Claim claim, ContainerTemplate expectedTemplate) {
        FingerprintRegistry.StructureStorageBlock block = claim.block();
        if (block.hasLootTable()) {
            ResourceLocation tableId = ResourceLocation.tryParse(block.lootTable());
            if (tableId == null) return null;
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                return ContainerRegistrar.applyTemplate(world, randomizable, expectedTemplate, false, claim.templateId());
            }
            return ContainerCompatibility.registerWithLootTable(world, be, tableId, block.lootTableSeed(), false, claim.templateId());
        }
        if (be instanceof RandomizableContainerBlockEntity randomizable) {
            return ContainerRegistrar.applyTemplate(world, randomizable, expectedTemplate, false, claim.templateId());
        }
        return ContainerCompatibility.registerWithItems(world, be, block.items(), false, claim.templateId());
    }

    private static String restoredDescription(FingerprintRegistry.StructureStorageBlock block) {
        if (block.hasLootTable()) return "loot table " + ResourceLocation.tryParse(block.lootTable());
        return block.items().size() + " baked item(s)";
    }

    private static ContainerTemplate expectedTemplate(BlockEntity be, FingerprintRegistry.StructureStorageBlock block) {
        if (block.hasLootTable()) {
            ResourceLocation tableId = ResourceLocation.tryParse(block.lootTable());
            if (tableId == null) return null;
            int size = be instanceof RandomizableContainerBlockEntity randomizable ? randomizable.getContainerSize() : sizeOf(be);
            return size > 0 ? new ContainerTemplate.LootTableTemplate(tableId, block.lootTableSeed(), size) : null;
        }
        int size = be instanceof RandomizableContainerBlockEntity randomizable ? randomizable.getContainerSize() : sizeOf(be);
        if (size <= 0) return null;
        return new ContainerTemplate.ItemListTemplate(ContainerCompatibility.toStacks(block.items(), size), size);
    }

    private static int sizeOf(BlockEntity be) {
        ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
        return inv == null ? 0 : inv.size();
    }

    private static boolean isRestorableType(BlockEntity be) {
        if (ContainerCompatibility.isSupportedContainer(be)) return true;
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    private static boolean isEmptyWithoutLootTable(BlockEntity be) {
        if (be instanceof RandomizableContainerBlockEntity randomizable) {
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
