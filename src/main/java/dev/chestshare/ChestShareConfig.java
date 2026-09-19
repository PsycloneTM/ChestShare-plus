package dev.chestshare;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** ChestShare+'s one runtime-toggleable setting: whether the passive scan is allowed to
 *  refill and share an EMPTY structure container it finds in an already-existing chunk.
 *  Toggled with {@code /chestshare toggle restore-empty-structures <on|off>}, not a config
 *  file to hand-edit - see {@link StructureContainerRestorer}.
 *
 *  Off by default: this refills and converts containers in chunks that existed before the mod
 *  was installed on nothing more than "empty, at the right position", and 0.3.1's critical fix
 *  was precisely about the mod touching storage it had no business touching. An admin who
 *  wants it turns it on, deliberately, each time they want it enabled after a value change.
 *
 *  The chosen value persists across restarts in a tiny one-line state file - written only when
 *  the command changes it, never on every boot - so admins aren't forced to re-run the command
 *  every session, while the toggle itself (not a hand-edited file) remains the only way to
 *  change it. A missing, unreadable or malformed state file never stops the server from
 *  starting - it just falls back to the default and says so in the log. */
public final class ChestShareConfig {
    private ChestShareConfig() {}

    private static final String STATE_FILE_NAME = "chestshare-plus.state";
    private static final boolean DEFAULT_RESTORE_EMPTY = false;

    private static volatile boolean restoreEmptyStructureContainers = DEFAULT_RESTORE_EMPTY;

    /** See {@link StructureContainerRestorer}. */
    public static boolean restoreEmptyStructureContainers() {
        return restoreEmptyStructureContainers;
    }

    /** Sets the toggle and persists it immediately. Returns true if the new value was
     *  successfully saved to disk (the in-memory value is applied either way, so a save
     *  failure only means the setting won't survive a restart - it still takes effect now). */
    public static boolean setRestoreEmptyStructureContainers(boolean value) {
        restoreEmptyStructureContainers = value;
        return saveState(value);
    }

    /** Loads the persisted value at startup, if any. Called once from ChestShare#onInitialize. */
    public static void load() {
        Path file = stateFile();
        if (Files.isRegularFile(file)) {
            try {
                String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (raw.equalsIgnoreCase("true")) {
                    restoreEmptyStructureContainers = true;
                } else if (raw.equalsIgnoreCase("false")) {
                    restoreEmptyStructureContainers = false;
                } else {
                    ChestShare.LOGGER.warn("[ChestShare] {} contained '{}', expected true/false - using default {}",
                            STATE_FILE_NAME, raw, DEFAULT_RESTORE_EMPTY);
                    restoreEmptyStructureContainers = DEFAULT_RESTORE_EMPTY;
                }
            } catch (IOException e) {
                ChestShare.LOGGER.warn("[ChestShare] could not read {} - using default {}: {}",
                        STATE_FILE_NAME, DEFAULT_RESTORE_EMPTY, e.toString());
                restoreEmptyStructureContainers = DEFAULT_RESTORE_EMPTY;
            }
        } else {
            restoreEmptyStructureContainers = DEFAULT_RESTORE_EMPTY;
        }
        ChestShare.LOGGER.info("[ChestShare] restore-empty-structures = {} (use /chestshare toggle restore-empty-structures <on|off> to change)",
                restoreEmptyStructureContainers);
    }

    private static boolean saveState(boolean value) {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Boolean.toString(value), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            ChestShare.LOGGER.warn("[ChestShare] could not save {} - the new value applies for this session "
                    + "but will not persist across a restart: {}", STATE_FILE_NAME, e.toString());
            return false;
        }
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(STATE_FILE_NAME);
    }
}
