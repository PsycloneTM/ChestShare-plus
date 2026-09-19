package dev.chestshare.compat;

import com.mojang.datafixers.util.Either;
import dev.chestshare.ChestShare;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out which structure template a jigsaw piece was built from. Shared by
 *  {@code /chestshare adopt-structure} and the passive empty-container restore in
 *  {@code StructureContainerRestorer}, so both agree on what "this piece came from template X"
 *  means. See command/NOTES.md for why the id has to be resolved this way in 1.21.1. */
public final class StructurePlacement {
    private StructurePlacement() {}

    private static final Pattern RESOURCE_LOCATION_TOKEN =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Element class -> its Either fields, already made accessible. Reflection over the class
     *  hierarchy is cheap once but the passive scan asks for the same handful of element classes
     *  on every empty container it looks at, so it is worth doing once per class. */
    private static final Map<Class<?>, List<Field>> EITHER_FIELDS = new ConcurrentHashMap<>();

    /** Resolves the structure template id of a jigsaw piece. Tries, in order:
     *  1. reflection on the element's Either&lt;ResourceLocation, StructureTemplate&gt; field, found by
     *     TYPE (not name) so it survives Mojmap -> intermediary remapping in production;
     *  2. extracting the first "namespace:path" token from the element's toString(), which is
     *     "Single[Left[ns:path]]" in 1.21.1 ("Single[ns:path]" in older versions).
     *  Returns null if neither works. */
    public static ResourceLocation resolveTemplateId(PoolElementStructurePiece piece) {
        Object element = piece.getElement();
        try {
            for (Field f : eitherFields(element.getClass())) {
                Object v = f.get(element);
                if (v instanceof Either<?, ?> either) {
                    Object left = either.left().orElse(null);
                    if (left instanceof ResourceLocation rl) return rl;
                }
            }
        } catch (Exception | LinkageError e) {
            ChestShare.LOGGER.debug("[ChestShare] reflective template id lookup failed, using toString parse: {}", e.toString());
        }
        try {
            return parseTemplateId(String.valueOf(element));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Field> eitherFields(Class<?> elementClass) {
        return EITHER_FIELDS.computeIfAbsent(elementClass, type -> {
            List<Field> found = new ArrayList<>();
            try {
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Either.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        found.add(f);
                    }
                }
            } catch (Exception | LinkageError e) {
                ChestShare.LOGGER.debug("[ChestShare] could not inspect {} for a template field: {}", type.getName(), e.toString());
            }
            return found;
        });
    }

    /** Pulls the first "namespace:path" token out of an element's toString(), ignoring any
     *  wrapper text such as "Single[Left[...]]" / "LegacySingle[Left[...]]". */
    public static ResourceLocation parseTemplateId(String elementString) {
        Matcher m = RESOURCE_LOCATION_TOKEN.matcher(elementString);
        return m.find() ? ResourceLocation.tryParse(m.group()) : null;
    }
}
