package dev.chestshare.compat;

import dev.chestshare.ChestShare;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds, at server start, a content fingerprint database for Sophisticated Storage /
 * CobbleFurnies containers baked into any currently-active structure template - see
 * compat/NOTES.md for the full design rationale (why content-only matching, why this
 * exists at all, and its known limitations).
 *
 * Nothing here is specific to any one modpack: it walks whatever structure .nbt files
 * are actually active on this server - mod-bundled or standalone datapacks alike - via
 * ResourceManager#listResources, the same merged view vanilla itself uses to resolve
 * structures. Reads the raw structure NBT directly with NbtIo rather than going through
 * StructureTemplate/StructureTemplateManager: the latter's palette-list accessor has no
 * confirmed public API (see NOTES.md), while NbtIo#readCompressed(InputStream, ...) is
 * the same stable, documented entry point ChestShareCommands already uses for import/export.
 */
public final class FingerprintRegistry {
    private FingerprintRegistry() {}

    /** A single baked, non-empty item stack as found in a structure template's block-entity NBT,
     *  including which inventory slot it occupies. Slot matters: Sophisticated Storage and
     *  CobbleFurnies both save Items as a sparse, non-sequential list (observed examples: slots
     *  0,3,5,7 in one barrel; slots 0,22,8,10 - not even ascending - in another), never a dense
     *  list where position i means slot i. Losing slot here silently misplaces every item when
     *  restoring baked contents into a real container. See NOTES.md. */
    public record FingerprintItem(int slot, String id, int count) {}

    /** A storage block as it appears in a structure template: its relative position within
     *  the template AND what the template says it holds - either exact item stacks baked into
     *  its block-entity NBT at generation time, or a loot table reference to roll from.
     *
     *  Both are needed, because the two kinds of world-generated container record their
     *  contents in completely different ways. Sophisticated Storage / CobbleFurnies bake real
     *  items. Vanilla-style lootable containers (vanilla chests and barrels, Cobblemon's gilded
     *  chest, and any other mod building on RandomizableContainerBlockEntity) bake only a
     *  `LootTable` string; their `items` is empty even though the container is very much not
     *  meant to be. Treating an empty items list as "the structure placed this empty" is exactly
     *  what made adopt-structure silently skip gilded chests - see command/NOTES.md.
     *
     *  {@code blockId} is the block the template places here (e.g. "minecraft:chest"), taken from
     *  the first palette; the passive restore requires the live block to be that same block before
     *  it touches anything. {@code multiPalette} is true when the template has more than one
     *  palette: the other palettes can place a different block or different baked contents at the
     *  same position and only the first is recorded, so a multi-palette block can't be trusted
     *  without an admin looking at it and is left to adopt-structure. */
    public record StructureStorageBlock(int x, int y, int z, List<FingerprintItem> items,
                                         String lootTable, long lootTableSeed,
                                         String blockId, boolean multiPalette) {
        public StructureStorageBlock(int x, int y, int z, List<FingerprintItem> items) {
            this(x, y, z, items, null, 0L, null, false);
        }
        /** The template rolls this container's contents from a loot table. */
        public boolean hasLootTable() { return lootTable != null && !lootTable.isEmpty(); }
        /** The template really does place this container with nothing in it (decorative). */
        public boolean emptyInTemplate() { return items.isEmpty() && !hasLootTable(); }
    }

    /** Kept for backward compat with ContainerScanner - just wraps the x/y/z of a StructureStorageBlock. */
    public record StoragePos(int x, int y, int z) {}

    // A Set<Set<...>>, not Set<List<...>>: the two examples above prove Items-list order
    // doesn't reliably track slot order, so comparing as Lists would make two structurally
    // identical containers fail to match purely because their NBT happened to serialize in
    // a different order. Comparing as unordered Sets of (slot, id, count) is order-proof.
    private static volatile Set<Set<FingerprintItem>> KNOWN_FINGERPRINTS = Set.of();
    /** Per-structure storage blocks (position + baked items) keyed by structure id. */
    private static volatile Map<ResourceLocation, List<StructureStorageBlock>> STRUCTURE_BLOCKS = Map.of();
    private static volatile boolean BUILT = false;

    public static void buildOnServerStart(MinecraftServer server) {
        Set<Set<FingerprintItem>> fingerprints = new HashSet<>();
        Map<ResourceLocation, List<StructureStorageBlock>> blocks = new HashMap<>();
        int structuresScanned = 0;
        int parseErrors = 0;

        var resources = server.getResourceManager().listResources("structure",
                loc -> loc.getPath().endsWith(".nbt"));

        for (var entry : resources.entrySet()) {
            ResourceLocation resourceLoc = entry.getKey();
            Resource resource = entry.getValue();
            // derive structure id from resource path: structure/<rel>.nbt -> <namespace>:<rel>
            String path = resourceLoc.getPath();
            String rel = path.startsWith("structure/") ? path.substring("structure/".length()) : path;
            if (rel.endsWith(".nbt")) rel = rel.substring(0, rel.length() - 4);
            ResourceLocation structureId = ResourceLocation.fromNamespaceAndPath(resourceLoc.getNamespace(), rel);

            try (InputStream in = resource.open()) {
                CompoundTag root = NbtIo.readCompressed(in, new NbtAccounter(64L * 1024L * 1024L, 16));
                structuresScanned++;
                List<StructureStorageBlock> blockList = new ArrayList<>();
                scanStructureNbt(root, fingerprints, blockList);
                if (!blockList.isEmpty()) blocks.put(structureId, blockList);
            } catch (IOException | RuntimeException e) {
                parseErrors++;
                ChestShare.LOGGER.warn("[ChestShare] Fingerprint scan: failed to read structure '{}', skipping it: {}",
                        resourceLoc, e.getMessage());
            }
        }

        if (structuresScanned > 0 && blocks.isEmpty()) {
            ChestShare.LOGGER.warn("[ChestShare] Fingerprint scan read {} structure(s) but recorded no storage blocks at all - "
                    + "either no active structure contains Sophisticated Storage/CobbleFurnies blocks, or structure NBT parsing is failing",
                    structuresScanned);
        }
        KNOWN_FINGERPRINTS = fingerprints;
        STRUCTURE_BLOCKS = blocks;
        BUILT = true;
        int withItems = 0, withLootTable = 0, emptyBlocks = 0;
        for (List<StructureStorageBlock> list : blocks.values()) {
            for (StructureStorageBlock b : list) {
                if (!b.items().isEmpty()) withItems++;
                else if (b.hasLootTable()) withLootTable++;
                else emptyBlocks++;
            }
        }
        ChestShare.LOGGER.info("[ChestShare] Fingerprint scan complete: {} structures scanned ({} parse errors), "
                        + "{} distinct storage-content fingerprints, {} structures with storage positions recorded "
                        + "({} block(s) with baked items, {} with a loot table, {} empty in the template)",
                structuresScanned, parseErrors, fingerprints.size(), blocks.size(),
                withItems, withLootTable, emptyBlocks);
    }

    /** True once buildOnServerStart has run. Matching before this returns false always
     *  fails closed (matches() returns false), rather than matching against an empty set
     *  that could be mistaken for "genuinely no fingerprints exist". */
    public static boolean isBuilt() {
        return BUILT;
    }

    /**
     * Returns the storage blocks (position + baked items) recorded for the given structure
     * ID, or null if that structure has no recorded storage blocks or hasn't been scanned
     * yet. Used by the adopt-structure command to match template positions to world positions
     * and restore the correct original item contents.
     */
    public static List<StructureStorageBlock> getStructureStorageBlocks(ResourceLocation structureId) {
        return STRUCTURE_BLOCKS.get(structureId);
    }

    /** Every structure template that has at least one recorded storage block, keyed by id.
     *  Used by adopt-structure's last-resort layout matcher when a piece's template id can't
     *  be resolved directly. Returned map is immutable. */
    public static Map<ResourceLocation, List<StructureStorageBlock>> allStructureBlocks() {
        return STRUCTURE_BLOCKS;
    }

    /** @deprecated use getStructureStorageBlocks which also carries baked item contents */
    @Deprecated
    public static List<StoragePos> getStructureStoragePositions(ResourceLocation structureId) {
        List<StructureStorageBlock> blocks = STRUCTURE_BLOCKS.get(structureId);
        if (blocks == null) return null;
        List<StoragePos> result = new ArrayList<>(blocks.size());
        for (StructureStorageBlock b : blocks) result.add(new StoragePos(b.x(), b.y(), b.z()));
        return result;
    }

    /** Whether the given live container's current contents exactly match some known,
     *  structure-baked fingerprint. See compat/NOTES.md for what this does and does not
     *  protect against. */
    public static boolean matchesKnownFingerprint(ContainerCompatibility.CompatInventory inv) {
        if (!BUILT || inv == null || inv.size() <= 0) return false;
        Set<FingerprintItem> current = new HashSet<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (stack == null || stack.isEmpty()) continue;
            current.add(new FingerprintItem(i, itemIdOf(stack), stack.getCount()));
        }
        if (current.isEmpty()) return false;
        return KNOWN_FINGERPRINTS.contains(current);
    }

    /** Registry id of a stack's item, e.g. "minecraft:diamond". */
    public static String idOf(ItemStack stack) {
        return itemIdOf(stack);
    }

    private static String itemIdOf(ItemStack stack) {
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key.toString();
    }

    // ---- NBT parsing, mirrors the validated Python reference implementation exactly ----

    private static void scanStructureNbt(CompoundTag root, Set<Set<FingerprintItem>> fingerprints,
                                          List<StructureStorageBlock> blocksOut) {
        List<ListTag> palettes = new ArrayList<>();
        if (root.contains("palettes")) {
            ListTag paletteList = root.getList("palettes", 9);
            for (int i = 0; i < paletteList.size(); i++) palettes.add((ListTag) paletteList.get(i));
        } else if (root.contains("palette")) {
            // "palette" is a list of COMPOUNDS (tag type 10). CompoundTag#getList returns an EMPTY
            // list when the requested element type doesn't match, so asking for 9 here silently
            // yielded nothing and the registry recorded no storage blocks for any single-palette
            // structure. (Only "palettes" - a list of LISTS - is type 9.)
            palettes.add(root.getList("palette", 10));
        } else {
            return;
        }
        if (!root.contains("blocks")) return;
        // "blocks" is likewise a list of compounds (type 10), not lists - see note above.
        ListTag blocks = root.getList("blocks", 10);
        ListTag firstPalette = palettes.get(0);

        for (ListTag palette : palettes) {
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag block = blocks.getCompound(i);
                int stateIndex = block.getInt("state");
                if (stateIndex < 0 || stateIndex >= palette.size()) continue;
                CompoundTag paletteEntry = (CompoundTag) palette.get(stateIndex);
                String blockId = paletteEntry.getString("Name");
                if (blockId == null || ContainerFingerprintFilter.isNeverAContainer(blockId)) continue;

                // Only record position/items once (from first palette), to avoid
                // duplicating entries across palette variants that share positions.
                if (palette != firstPalette || !block.contains("pos")) continue;
                ListTag posTag = block.getList("pos", 3);
                if (posTag.size() != 3) continue;

                CompoundTag beNbt = block.contains("nbt") ? block.getCompound("nbt") : null;
                List<FingerprintItem> items = beNbt == null ? List.of() : extractItems(beNbt);
                // A baked LootTable is the only record a vanilla-style loot container leaves
                // behind - see StructureStorageBlock's javadoc. It is also a definitive
                // "this is world-generated loot" signal on its own, so a block carrying one is
                // recorded whatever its id.
                String lootTable = beNbt != null && beNbt.contains("LootTable")
                        ? beNbt.getString("LootTable") : null;
                if (lootTable != null && lootTable.isEmpty()) lootTable = null;
                long lootSeed = beNbt != null && beNbt.contains("LootTableSeed")
                        ? beNbt.getLong("LootTableSeed") : 0L;

                boolean record = lootTable != null
                        || ContainerFingerprintFilter.isStorageBlock(blockId)
                        || ContainerFingerprintFilter.looksLikeContainer(blockId);
                if (!record) continue;

                blocksOut.add(new StructureStorageBlock(
                        posTag.getInt(0), posTag.getInt(1), posTag.getInt(2), items, lootTable, lootSeed,
                        blockId, palettes.size() > 1));
                if (!items.isEmpty()) fingerprints.add(Set.copyOf(items));
            }
        }
    }

    /** Reads baked item stacks out of a structure block's saved NBT. Handles the two known
     *  shapes: Sophisticated Storage's nested storageWrapper.contents.inventory.Items path,
     *  and CobbleFurnies' direct vanilla-style Items list. Deliberately never reads
     *  storageWrapper.renderInfo - that's Sophisticated Storage's external display-item
     *  metadata (what's shown floating on the outside of the block), not real inventory
     *  contents; an earlier, offline extraction pass mistakenly included it, inflating a
     *  small number of "limited barrel" fingerprints by one phantom item. See NOTES.md. */
    private static List<FingerprintItem> extractItems(CompoundTag blockEntityNbt) {
        List<FingerprintItem> result = new ArrayList<>();
        ListTag items = null;

        if (blockEntityNbt.contains("storageWrapper")) {
            CompoundTag sw = blockEntityNbt.getCompound("storageWrapper");
            if (sw.contains("contents")) {
                CompoundTag contents = sw.getCompound("contents");
                if (contents.contains("inventory")) {
                    CompoundTag inventory = contents.getCompound("inventory");
                    if (inventory.contains("Items")) items = inventory.getList("Items", 10);
                }
            }
        } else if (blockEntityNbt.contains("Items")) {
            items = blockEntityNbt.getList("Items", 10);
        }
        if (items == null) return result;

        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (!entry.contains("id")) continue;
            String id = entry.getString("id");
            if (id == null || id.isEmpty()) continue;
            int count = entry.contains("count") ? entry.getInt("count")
                    : entry.contains("Count") ? entry.getInt("Count") : 1;
            // Every real example checked (both SS's storageWrapper path and CobbleFurnies'
            // plain Items list) carried an explicit Slot - vanilla's own container NBT
            // convention. Falling back to list index i only protects against some future
            // storage mod that omits it; it is not what SS/CF actually do.
            int slot = entry.contains("Slot") ? entry.getInt("Slot") : i;
            result.add(new FingerprintItem(slot, id, count));
        }
        return result;
    }
}
