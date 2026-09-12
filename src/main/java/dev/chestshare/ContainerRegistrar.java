package dev.chestshare;

import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.ArrayList;
import java.util.List;

public final class ContainerRegistrar {
    private ContainerRegistrar() {}
    public static SharedContainerEntry register(ServerLevel world, RandomizableContainerBlockEntity container) { return register(world, container, true); }
    public static SharedContainerEntry register(ServerLevel world, RandomizableContainerBlockEntity container, boolean markDirty) {
        // Order matters here - see NOTES.md
        var lootTable = container.getLootTable();
        ContainerTemplate template;

        if (lootTable != null) {
            template = new ContainerTemplate.LootTableTemplate(
                    lootTable.location(),
                    container.getLootTableSeed(),
                    container.getContainerSize());
        } else {
            List<ItemStack> held = copyContents(container);
            boolean empty = held.stream().allMatch(ItemStack::isEmpty);
            if (empty) return null;
            template = new ContainerTemplate.ItemListTemplate(held, container.getContainerSize());
        }

        return applyTemplate(world, container, template, markDirty);
    }
    public static SharedContainerEntry registerForced(ServerLevel world, RandomizableContainerBlockEntity container) {
        return applyTemplate(world, container, new ContainerTemplate.ItemListTemplate(copyContents(container), container.getContainerSize()));
    }
    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container, ContainerTemplate template) { return applyTemplate(world, container, template, true); }
    public static SharedContainerEntry applyTemplate(ServerLevel world, RandomizableContainerBlockEntity container, ContainerTemplate template, boolean markDirty) {
        // Order matters here - see NOTES.md
        container.setLootTable(null);
        container.setLootTableSeed(0L);
        for (int i=0;i<container.getContainerSize();i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
        ((SharedMarker)container).chestshare$setShared(true);
        if (markDirty) container.setChanged();
        SharedContainerEntry entry = new SharedContainerEntry(template);
        SharedContainersState.get(world).putBlock(container.getBlockPos(), entry);
        ChestShare.LOGGER.debug("Registered shared container at {} in {}", container.getBlockPos(), world.dimension().location());
        return entry;
    }
    public static List<ItemStack> copyContents(Container container) {
        List<ItemStack> out = new ArrayList<>(container.getContainerSize());
        for (int i=0;i<container.getContainerSize();i++) out.add(container.getItem(i).copy());
        return out;
    }
}
