package dev.chestshare.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public sealed interface ContainerTemplate permits ContainerTemplate.LootTableTemplate, ContainerTemplate.ItemListTemplate {
    int size();
    CompoundTag toNbt(HolderLookup.Provider registries);
    List<ItemStack> createStacks(ServerLevel world, Vec3 origin, ServerPlayer player, long fallbackSeed, int listSize, HolderLookup.Provider registries);

    static ContainerTemplate fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        String type = nbt.getString("Type");
        return switch (type) {
            case "loot_table" -> new LootTableTemplate(ResourceLocation.parse(nbt.getString("LootTable")), nbt.getLong("Seed"), nbt.getInt("Size"));
            case "items" -> {
                int size = nbt.getInt("Size");
                ListTag list = nbt.getList("Items", 10);
                List<ItemStack> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) items.add(ItemStack.EMPTY);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag entry = list.getCompound(i);
                    int slot = entry.contains("Slot") ? entry.getInt("Slot") : i;
                    if (slot >= 0 && slot < size) {
                        items.set(slot, ItemStack.parseOptional(registries, entry));
                    }
                }
                yield new ItemListTemplate(items, size);
            }
            default -> throw new IllegalStateException("Unknown container template type: " + type);
        };
    }

    record LootTableTemplate(ResourceLocation lootTable, long seed, int size) implements ContainerTemplate {
        @Override public CompoundTag toNbt(HolderLookup.Provider registries) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("Type", "loot_table");
            nbt.putString("LootTable", lootTable.toString());
            nbt.putLong("Seed", seed);
            nbt.putInt("Size", size);
            return nbt;
        }

        @Override public List<ItemStack> createStacks(ServerLevel world, Vec3 origin, ServerPlayer player, long fallbackSeed, int listSize, HolderLookup.Provider registries) {
            List<ItemStack> out = new ArrayList<>(listSize);
            for (int i = 0; i < listSize; i++) out.add(ItemStack.EMPTY);
            // Must look up via server.reloadableRegistries(), not world.registryAccess() -
            // see NOTES.md (this was a real "container permanently empty" bug).
            LootTable table;
            var holder = world.getServer().reloadableRegistries().lookup()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE)
                    .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, lootTable));
            if (holder.isEmpty()) return out;
            table = holder.get().value();
            LootParams params = new LootParams.Builder(world)
                    .withParameter(LootContextParams.ORIGIN, origin)
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .withLuck(player.getLuck())
                    .create(LootContextParamSets.CHEST);
            // Must use fill() against a scratch container, not getRandomItems() - see
            // NOTES.md (this was a real "loot packed at the start" bug).
            net.minecraft.world.SimpleContainer buffer = new net.minecraft.world.SimpleContainer(listSize);
            table.fill(buffer, params, seed != 0L ? seed : fallbackSeed);
            for (int i = 0; i < listSize; i++) out.set(i, buffer.getItem(i).copy());
            return out;
        }
    }

    record ItemListTemplate(List<ItemStack> items, int size) implements ContainerTemplate {
        public ItemListTemplate { items = items.stream().map(ItemStack::copy).toList(); }
        @Override public CompoundTag toNbt(HolderLookup.Provider registries) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("Type", "items");
            nbt.putInt("Size", size);
            ListTag list = new ListTag();
            for (int i = 0; i < size; i++) {
                ItemStack stack = items.get(i);
                // Can't encode ItemStack.EMPTY - see NOTES.md
                if (stack == null || stack.isEmpty()) continue;
                CompoundTag entry = (CompoundTag) stack.save(registries);
                entry.putInt("Slot", i);
                list.add(entry);
            }
            nbt.put("Items", list);
            return nbt;
        }
        @Override public List<ItemStack> createStacks(ServerLevel world, Vec3 origin, ServerPlayer player, long fallbackSeed, int listSize, HolderLookup.Provider registries) {
            List<ItemStack> out = new ArrayList<>(listSize);
            for (int i = 0; i < listSize; i++) out.add(i < items.size() ? items.get(i).copy() : ItemStack.EMPTY);
            return out;
        }
    }
}
