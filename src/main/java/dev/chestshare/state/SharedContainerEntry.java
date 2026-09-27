package dev.chestshare.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public final class SharedContainerEntry {
    private final ContainerTemplate template;
    /** Template id when this registration was created by the automatic structure-restorer.
     *  Null means the registration came from the normal/manual sharing paths, or is a legacy
     *  entry written before provenance was recorded. */
    private final ResourceLocation structureTemplate;
    private final Map<UUID, List<ItemStack>> instances = new HashMap<>();
    public SharedContainerEntry(ContainerTemplate template) { this(template, null); }
    public SharedContainerEntry(ContainerTemplate template, ResourceLocation structureTemplate) {
        this.template = template;
        this.structureTemplate = structureTemplate;
    }
    public ResourceLocation structureTemplate() { return structureTemplate; }
    public ContainerTemplate template() { return template; }
    public List<ItemStack> getInstance(UUID id) { return instances.get(id); }
    public void putInstance(UUID id, List<ItemStack> stacks) { instances.put(id, copy(stacks)); }
    public void clearInstances() { instances.clear(); }
    public CompoundTag toNbt(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        nbt.put("Template", template.toNbt(registries));
        if (structureTemplate != null) nbt.putString("StructureTemplate", structureTemplate.toString());
        ListTag list = new ListTag();
        instances.forEach((id, stacks) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("Player", id);
            e.putInt("Size", stacks.size());
            ListTag items = new ListTag();
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack stack = stacks.get(slot);
                // Can't encode ItemStack.EMPTY - see NOTES.md
                if (stack == null || stack.isEmpty()) continue;
                CompoundTag item = (CompoundTag) stack.save(registries);
                item.putInt("Slot", slot);
                items.add(item);
            }
            e.put("Items", items);
            list.add(e);
        });
        nbt.put("Instances", list);
        return nbt;
    }

    public static SharedContainerEntry fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        ResourceLocation structureTemplate = null;
        if (nbt.contains("StructureTemplate")) {
            structureTemplate = ResourceLocation.tryParse(nbt.getString("StructureTemplate"));
        }
        SharedContainerEntry entry = new SharedContainerEntry(
                ContainerTemplate.fromNbt(nbt.getCompound("Template"), registries), structureTemplate);
        ListTag list = nbt.getList("Instances", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int size = e.getInt("Size");
            ListTag items = e.getList("Items", 10);
            List<ItemStack> stacks = new ArrayList<>(size);
            for (int slot = 0; slot < size; slot++) stacks.add(ItemStack.EMPTY);
            for (int j = 0; j < items.size(); j++) {
                CompoundTag item = items.getCompound(j);
                int slot = item.contains("Slot") ? item.getInt("Slot") : j;
                if (slot >= 0 && slot < size) {
                    stacks.set(slot, ItemStack.parseOptional(registries, item));
                }
            }
            entry.instances.put(e.getUUID("Player"), stacks);
        }
        return entry;
    }
    private static List<ItemStack> copy(List<ItemStack> in) { return in.stream().map(ItemStack::copy).toList(); }
}
