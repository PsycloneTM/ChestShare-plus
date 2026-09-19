package dev.chestshare.compat;

import dev.chestshare.ChestShare;
import dev.chestshare.SharedMarker;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Optional compatibility for storage block entities which do not implement vanilla Container. */
public final class ContainerCompatibility {
    private ContainerCompatibility() {}

    private static final Logger LOGGER = ChestShare.LOGGER;
    private static final Set<String> WARNED_CLASSES = new HashSet<>();

    public static boolean isSupportedContainer(BlockEntity be) {
        if (be == null || be.getClass().getName().startsWith("net.minecraft.")) return false;
        return getContainerInventory(be) != null;
    }

    public static CompatInventory getContainerInventory(BlockEntity be) {
        if (be instanceof Container c) return new VanillaCompatInventory(c);
        Handler h = findHandler(be);
        return h == null ? null : new ReflectiveCompatInventory(h);
    }

    /** Logs a diagnostic once per block-entity class per session, so reflection mismatches
     *  are visible in the log instead of silently doing nothing. */
    private static void warnOnce(BlockEntity be, String message) {
        String className = be.getClass().getName();
        if (WARNED_CLASSES.add(className)) {
            LOGGER.warn("[ChestShare] '{}' {}", className, message);
        }
    }

    public static SharedContainerEntry register(ServerLevel world, BlockEntity be, boolean markDirty) {
        if (!(be instanceof SharedMarker marker) || marker.chestshare$isShared()) return null;
        CompatInventory inv = getContainerInventory(be);
        if (inv == null || inv.size() <= 0) return null;

        List<ItemStack> copy = new ArrayList<>(inv.size());
        boolean any = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack == null) stack = ItemStack.EMPTY;
            else stack = stack.copy();
            if (!stack.isEmpty()) any = true;
            copy.add(stack);
        }
        if (!any) return null;

        SharedContainerEntry entry = new SharedContainerEntry(new ContainerTemplate.ItemListTemplate(copy, inv.size()));
        for (int i = 0; i < inv.size(); i++) inv.set(i, ItemStack.EMPTY);
        marker.chestshare$setShared(true);
        if (markDirty) be.setChanged();
        SharedContainersState state = SharedContainersState.get(world);
        state.putBlock(be.getBlockPos(), entry);
        state.setDirty();
        LOGGER.info("[ChestShare] compat-registered '{}' at {} in {} ({} slots, {} non-empty)",
                be.getClass().getName(), be.getBlockPos(), world.dimension().location(),
                inv.size(), copy.stream().filter(s -> !s.isEmpty()).count());
        return entry;
    }

    /**
     * Registers (or re-registers) a compat container with a specific item list from the
     * structure template, rather than stealing the container's current live contents.
     * Used by /chestshare adopt-structure to restore original baked loot regardless of
     * whether the container has already been looted, emptied, or registered with wrong
     * contents. Unlike register(), this succeeds even if the live container is empty,
     * and replaces any existing SharedContainersState entry for this position.
     */
    public static SharedContainerEntry registerWithItems(ServerLevel world, BlockEntity be,
            List<FingerprintRegistry.FingerprintItem> bakedItems, boolean markDirty) {
        if (!(be instanceof SharedMarker marker)) return null;
        CompatInventory inv = getContainerInventory(be);
        if (inv == null || inv.size() <= 0) return null;

        List<ItemStack> stacks = toStacks(bakedItems, inv.size());

        // Clear the live container (whatever it currently holds)
        for (int i = 0; i < inv.size(); i++) inv.set(i, ItemStack.EMPTY);
        marker.chestshare$setShared(true);
        if (markDirty) be.setChanged();

        SharedContainerEntry entry = new SharedContainerEntry(
                new ContainerTemplate.ItemListTemplate(stacks, inv.size()));
        SharedContainersState state = SharedContainersState.get(world);
        state.putBlock(be.getBlockPos(), entry);
        state.setDirty();
        long nonEmpty = stacks.stream().filter(s -> !s.isEmpty()).count();
        LOGGER.debug("[ChestShare] adopt-registered '{}' at {} in {} ({} baked items from template)",
                be.getClass().getName(), be.getBlockPos(), world.dimension().location(), nonEmpty);
        return entry;
    }

    /**
     * Builds an inventory-sized ItemStack list out of a template's baked FingerprintItems,
     * placing each one at its recorded slot - NOT by list position. Sophisticated Storage and
     * CobbleFurnies both save Items as a sparse, non-sequential list (e.g. slots 0,22,8,10 in
     * one real barrel), so treating list position i as slot i silently scrambles every item
     * into the wrong slot. See FingerprintItem's javadoc and compat/NOTES.md.
     */
    public static List<ItemStack> toStacks(List<FingerprintRegistry.FingerprintItem> bakedItems, int size) {
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) stacks.add(ItemStack.EMPTY);
        for (FingerprintRegistry.FingerprintItem fi : bakedItems) {
            int slot = fi.slot();
            if (slot < 0 || slot >= size) continue;
            ResourceLocation itemId = ResourceLocation.tryParse(fi.id());
            if (itemId == null) continue;
            // BuiltInRegistries.ITEM is a DefaultedRegistry<Item>: plain get(id) never
            // returns null, it silently falls back to minecraft:air for an unknown id -
            // which would plant a bogus air stack instead of skipping it. getOptional(id)
            // is the variant that actually reports "not registered" as empty.
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getOptional(itemId).orElse(null);
            if (item != null) stacks.set(slot, new ItemStack(item, fi.count()));
        }
        return stacks;
    }

    /**
     * Registers (or re-registers) a compat container against a loot table taken from the
     * structure template, for containers whose template bakes a `LootTable` rather than real
     * items (Cobblemon's gilded chest and friends). The live container is emptied and every
     * player rolls the table for themselves on open, exactly like /chestshare convert does.
     *
     * Only for containers that are NOT RandomizableContainerBlockEntity - those have a native
     * loot-table slot on the block entity itself that must also be cleared, which is
     * ContainerRegistrar.applyTemplate's job.
     */
    public static SharedContainerEntry registerWithLootTable(ServerLevel world, BlockEntity be,
            ResourceLocation lootTable, long seed, boolean markDirty) {
        if (!(be instanceof SharedMarker marker) || lootTable == null) return null;
        CompatInventory inv = getContainerInventory(be);
        if (inv == null || inv.size() <= 0) return null;

        for (int i = 0; i < inv.size(); i++) inv.set(i, ItemStack.EMPTY);
        marker.chestshare$setShared(true);
        if (markDirty) be.setChanged();

        SharedContainerEntry entry = new SharedContainerEntry(
                new ContainerTemplate.LootTableTemplate(lootTable, seed, inv.size()));
        SharedContainersState state = SharedContainersState.get(world);
        state.putBlock(be.getBlockPos(), entry);
        state.setDirty();
        LOGGER.debug("[ChestShare] adopt-registered '{}' at {} in {} (loot table {}, seed {})",
                be.getClass().getName(), be.getBlockPos(), world.dimension().location(), lootTable, seed);
        return entry;
    }

    public interface CompatInventory {
        int size();
        ItemStack get(int slot);
        void set(int slot, ItemStack stack);
    }

    private static final class VanillaCompatInventory implements CompatInventory {
        private final Container c;
        VanillaCompatInventory(Container c) { this.c = c; }
        public int size() { return c.getContainerSize(); }
        public ItemStack get(int slot) { return c.getItem(slot); }
        public void set(int slot, ItemStack stack) { c.setItem(slot, stack); }
    }

    private record Handler(Object target, int fixedSize, Method getMethod, Method setMethod, Method listMethod, Field listField) {
        int size() {
            if (fixedSize > 0) return fixedSize;
            try { return currentList().size(); }
            catch (Throwable ignored) { return 0; }
        }
        List<?> currentList() throws Exception {
            Object value = listField != null ? listField.get(target) : listMethod.invoke(target);
            return (List<?>) value;
        }
        ItemStack get(int slot) {
            try {
                Object value = getMethod != null ? getMethod.invoke(target, slot) : currentList().get(slot);
                return value instanceof ItemStack s ? s : ItemStack.EMPTY;
            } catch (Throwable t) { return ItemStack.EMPTY; }
        }
        void set(int slot, ItemStack stack) {
            try {
                if (setMethod != null) setMethod.invoke(target, slot, stack);
                else ((List<ItemStack>) currentList()).set(slot, stack);
            } catch (Throwable ignored) {}
        }
    }

    private static final class ReflectiveCompatInventory implements CompatInventory {
        private final Handler h;
        ReflectiveCompatInventory(Handler h) { this.h = h; }
        public int size() { return h.size(); }
        public ItemStack get(int slot) { return h.get(slot); }
        public void set(int slot, ItemStack stack) { h.set(slot, stack); }
    }

    private static Handler findHandler(BlockEntity be) {
        // Sophisticated Storage reflection lookup - see NOTES.md for the full history
        // of why this walks method names this way (three earlier silent-failure bugs).
        try {
            Method wrapper = publicOrDeclaredMethod(be.getClass(), "getStorageWrapper");
            if (wrapper == null) {
                warnOnce(be, "has no getStorageWrapper() method (tried public API and full "
                        + "declared-method superclass walk) — Sophisticated Storage compat "
                        + "does not apply to this block entity.");
            } else {
                Object storageWrapper = wrapper.invoke(be);
                if (storageWrapper == null) {
                    warnOnce(be, "getStorageWrapper() returned null.");
                } else {
                    Method invMethod = publicOrDeclaredMethod(storageWrapper.getClass(), "getInventoryHandler");
                    if (invMethod == null) {
                        warnOnce(be, "storage wrapper '" + storageWrapper.getClass().getName()
                                + "' has no getInventoryHandler() method.");
                    } else {
                        Object inv = invMethod.invoke(storageWrapper);
                        if (inv == null) {
                            warnOnce(be, "storage wrapper '" + storageWrapper.getClass().getName()
                                    + "' getInventoryHandler() returned null.");
                        } else {
                            int slots = 0;
                            Method wrapperSlots = findMethod(storageWrapper.getClass(), 0, "getNumberOfInventorySlots");
                            if (wrapperSlots != null) {
                                try {
                                    Object v = wrapperSlots.invoke(storageWrapper);
                                    if (v instanceof Number n) slots = n.intValue();
                                } catch (Throwable t) {
                                    warnOnce(be, "storage wrapper '" + storageWrapper.getClass().getName()
                                            + "' getNumberOfInventorySlots() invoke failed: " + t);
                                }
                            }
                            if (slots <= 0) {
                                for (String name : new String[]{"getSlotCount", "getSlots", "getNumberOfInventorySlots", "size"}) {
                                    Method candidate = findMethod(inv.getClass(), 0, name);
                                    if (candidate == null) continue;
                                    try {
                                        Object v = candidate.invoke(inv);
                                        if (v instanceof Number n) {
                                            slots = n.intValue();
                                            break;
                                        }
                                    } catch (Throwable t) {
                                        warnOnce(be, "found inventory handler '" + inv.getClass().getName()
                                                + "' but invoking slot-count method '" + candidate.getName()
                                                + "' failed: " + t);
                                    }
                                }
                            }
                            if (slots > 0) {
                                Method get = findMethod(inv.getClass(), 1,
                                        "getStackInSlot", "getSlotStack", "getStack", "getItem");
                                Method set = findMethod(inv.getClass(), 2,
                                        "setStackInSlot", "setSlotStack", "setStack", "setItem");
                                if (get != null && set != null) {
                                    return new Handler(inv, slots, get, set, null, null);
                                } else {
                                    warnOnce(be, "found inventory handler '" + inv.getClass().getName()
                                            + "' with " + slots + " slots, but no matching get/set stack "
                                            + "method (tried getStackInSlot/getSlotStack/getStack/getItem "
                                            + "and their set counterparts).");
                                }
                            } else {
                                warnOnce(be, "found inventory handler '" + inv.getClass().getName()
                                        + "' but could not determine its slot count (tried "
                                        + "getSlotCount/getSlots/getNumberOfInventorySlots/size, "
                                        + "none returned a Number).");
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            warnOnce(be, "Sophisticated Storage compat lookup threw " + t.getClass().getName()
                    + ": " + t.getMessage());
        }

        // CobbleFurnies compat - see NOTES.md
        if (be.getClass().getName().startsWith("com.lunazstudios.cobblefurnies.")) {
            try {
                Method list = findMethod(be.getClass(), "getItems", "method_11282");
                Method size = findMethod(be.getClass(), "getContainerSize", "method_5439");
                if (list != null && size != null) {
                    list.setAccessible(true);
                    Object value = list.invoke(be);
                    if (value instanceof List<?>) {
                        int slots = ((Number) size.invoke(be)).intValue();
                        return new Handler(be, slots, null, null, list, null);
                    }
                }
                Field items = findField(be.getClass(), "items");
                if (items != null) {
                    items.setAccessible(true);
                    Object value = items.get(be);
                    if (value instanceof List<?>) {
                        Method sizeMethod = findMethod(be.getClass(), "getContainerSize", "method_5439");
                        int slots = sizeMethod == null ? ((List<?>) value).size() : ((Number) sizeMethod.invoke(be)).intValue();
                        return new Handler(be, slots, null, null, null, items);
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to generic support.
            }
        }

        // Generic modded Container support for other inventory block entities.
        if (be instanceof Container c) {
            Method get = findMethod(c.getClass(), "getItem", "method_5438");
            Method set = findMethod(c.getClass(), "setItem", "method_5447");
            if (get != null && set != null) {
                return new Handler(c, c.getContainerSize(), get, set, null, null);
            }
        }

        return null;
    }

    private static Method publicOrDeclaredMethod(Class<?> type, String name, Class<?>... params) {
        try {
            Method m = type.getMethod(name, params);
            try { m.setAccessible(true); } catch (Throwable ignored) {}
            return m;
        } catch (NoSuchMethodException ignored) {
            // Search the class hierarchy for non-public APIs used by modded block entities.
            if (params.length == 0) return findMethod(type, name);
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    try { m.setAccessible(true); } catch (Throwable ignored2) {}
                    return m;
                } catch (NoSuchMethodException ignored2) {}
            }
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String... names) {
        for (String name : names) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        try { m.setAccessible(true); } catch (Throwable ignored) {}
                        return m;
                    }
                }
            }
        }
        return null;
    }

    /** Like findMethod, but matches only methods with the given parameter count - see NOTES.md. */
    private static Method findMethod(Class<?> type, int paramCount, String... names) {
        for (String name : names) {
            try {
                for (Method m : type.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                        // setAccessible required even here - see NOTES.md
                        try { m.setAccessible(true); } catch (Throwable ignored2) {}
                        return m;
                    }
                }
            } catch (Throwable ignored) {}
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                        try { m.setAccessible(true); } catch (Throwable ignored) {}
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
