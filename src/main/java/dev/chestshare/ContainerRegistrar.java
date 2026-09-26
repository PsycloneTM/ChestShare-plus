package dev.chestshare;

import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.ArrayList;
import java.util.List;

public final class ContainerRegistrar {
    private ContainerRegistrar() {}

    public static SharedContainerEntry register(ServerLevel world, RandomizableContainerBlockEntity container) {
        return register(world, container, true);
    }

    public static SharedContainerEntry register(ServerLevel world, RandomizableContainerBlockEntity container, boolean markDirty) {
        if (container instanceof SharedMarker marker && marker.chestshare$isShared()) return null;
        SharedContainersState state = SharedContainersState.get(world);
        if (state.getBlock(container.getBlockPos()) != null) return null;

        ContainerTemplate template = captureTemplate(container);
        if (template == null) return null;
        return applyTemplate(world, container, template, markDirty, null);
    }

    public static SharedContainerEntry registerForced(ServerLevel world, RandomizableContainerBlockEntity container) {
        if (container instanceof SharedMarker marker && marker.chestshare$isShared()) return null;
        SharedContainersState state = SharedContainersState.get(world);
        if (state.getBlock(container.getBlockPos()) != null) return null;

        ContainerTemplate template = captureTemplate(container);
        if (template == null) return null;
        return applyTemplate(world, container, template, true, null);
    }

    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container,
            ContainerTemplate template) {
        return applyTemplate(world, container, template, true, null);
    }

    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container,
            ContainerTemplate template, boolean markDirty) {
        return applyTemplate(world, container, template, markDirty, null);
    }

    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container,
            ContainerTemplate template, boolean markDirty, ResourceLocation structureTemplate) {
        return applyTemplate(world, container, template, markDirty, structureTemplate, false);
    }

    /** Explicit replace: registers {@code template} over whatever this position already has,
     *  shared or not. Only for /chestshare adopt-structure's precise path, which holds the
     *  structure template's own record of what the container should be and has been told (see
     *  command/NOTES.md) to replace wrong existing state with it. Every player's cached
     *  per-player copy of the OLD entry is discarded along with it - the new entry starts with
     *  no instances, so everyone rolls afresh on next open. */
    public static SharedContainerEntry applyTemplateReplacing(ServerLevel world,
            RandomizableContainerBlockEntity container, ContainerTemplate template, boolean markDirty) {
        return applyTemplate(world, container, template, markDirty, null, true);
    }

    /** @param replaceExisting bypass the "already shared / already has an entry" guard. Never
     *  true from the passive scanner, convert or the open path - those must not overwrite. */
    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container,
            ContainerTemplate template, boolean markDirty, ResourceLocation structureTemplate,
            boolean replaceExisting) {
        // Without replaceExisting this never overwrites a container that is already shared or
        // already has a saved ChestShare entry, whatever the provenance. structureTemplate is
        // only recorded on the new entry.
        SharedContainersState state = SharedContainersState.get(world);
        if (!replaceExisting) {
            if (container instanceof SharedMarker marker && marker.chestshare$isShared()) {
                return null;
            }
            if (state.getBlock(container.getBlockPos()) != null) {
                return null;
            }
        }

        container.setLootTable(null);
        container.setLootTableSeed(0L);
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
        if (container instanceof SharedMarker marker) {
            marker.chestshare$setShared(true);
        }
        if (markDirty) container.setChanged();

        SharedContainerEntry entry = new SharedContainerEntry(template, structureTemplate);
        state.putBlock(container.getBlockPos(), entry);
        state.setDirty();
        ChestShare.LOGGER.debug("Registered shared container at {} in {}", container.getBlockPos(), world.dimension().location());
        return entry;
    }

    public static List<ItemStack> copyContents(Container container) {
        List<ItemStack> out = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            out.add(container.getItem(i).copy());
        }
        return out;
    }

    private static ContainerTemplate captureTemplate(RandomizableContainerBlockEntity container) {
        var lootTable = container.getLootTable();
        if (lootTable != null) {
            return new ContainerTemplate.LootTableTemplate(
                    lootTable.location(),
                    container.getLootTableSeed(),
                    container.getContainerSize());
        }

        List<ItemStack> held = copyContents(container);
        if (held.stream().allMatch(ItemStack::isEmpty)) return null;
        return new ContainerTemplate.ItemListTemplate(held, container.getContainerSize());
    }
}
