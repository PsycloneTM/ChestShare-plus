package dev.chestshare.compat;

/** Identifies block ids that are storage containers when scanning structure templates.
 *
 *  Three separate questions, deliberately kept apart:
 *  - {@link #isStorageBlock} — Sophisticated Storage / CobbleFurnies storage (as opposed to
 *    CobbleFurnies' purely decorative blocks - sofas, tables, mosaics, etc., which share the
 *    same mod namespace but have no inventory). These are the mods whose containers bake their
 *    loot directly into the template as items.
 *  - {@link #looksLikeContainer} — any namespace's chest/barrel/shulker-style block. Needed
 *    because loot containers from OTHER mods (Cobblemon's gilded chest, for one) and vanilla's
 *    own chests do not bake items at all: they bake a LootTable reference, and once a player
 *    has opened one, nothing about the block in the world says what it used to hold. The
 *    template is the only remaining record, so those positions have to be recorded too or
 *    adopt-structure can never see them.
 *  - {@link #isNeverAContainer} — explicit exclusions for blocks that match the name check but
 *    hold nothing adoptable (ender chests). Recording those would put positions in a template's
 *    layout that no live container can ever satisfy, which breaks the exact set-equality test in
 *    inferTemplateByLayout and drags down StructureAligner's coverage score.
 *
 *  Used only by FingerprintRegistry when scanning structure templates offline; the live-world
 *  path identifies real containers by their actual block entity class/API shape instead, which
 *  is a stronger signal than an id-string guess. Keep the two sides in agreement: see
 *  ChestShareCommands#isAdoptableContainer, which is the live-world counterpart of this filter. */
final class ContainerFingerprintFilter {
    private ContainerFingerprintFilter() {}

    private static final String[] COBBLEFURNIES_STORAGE_KEYWORDS = {"cabinet", "drawer", "cabinetry"};

    /** Namespace-agnostic storage-block name fragments. Matched against the PATH only, so a
     *  mod namespace that happens to contain one of these words can't drag in every block it
     *  registers. */
    private static final String[] GENERIC_STORAGE_KEYWORDS = {
            "chest", "barrel", "shulker_box", "crate", "strongbox", "lockbox", "footlocker"
    };

    /** Matched against the path as well; checked before the keyword list. */
    private static final String[] NOT_CONTAINER_KEYWORDS = {"ender_chest"};

    static boolean isStorageBlock(String blockId) {
        if (blockId.startsWith("sophisticatedstorage:")) return true;
        if (blockId.startsWith("cobblefurnies:")) {
            String lower = blockId.toLowerCase(java.util.Locale.ROOT);
            for (String keyword : COBBLEFURNIES_STORAGE_KEYWORDS) {
                if (lower.contains(keyword)) return true;
            }
        }
        return false;
    }

    /** True for chest/barrel/shulker-shaped blocks from any mod, vanilla included. */
    static boolean looksLikeContainer(String blockId) {
        if (isNeverAContainer(blockId)) return false;
        String path = pathOf(blockId);
        for (String keyword : GENERIC_STORAGE_KEYWORDS) {
            if (path.contains(keyword)) return true;
        }
        return false;
    }

    /** Blocks that read as containers by name but hold nothing this mod can adopt. */
    static boolean isNeverAContainer(String blockId) {
        String path = pathOf(blockId);
        for (String keyword : NOT_CONTAINER_KEYWORDS) {
            if (path.contains(keyword)) return true;
        }
        return false;
    }

    private static String pathOf(String blockId) {
        String lower = blockId.toLowerCase(java.util.Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }
}
