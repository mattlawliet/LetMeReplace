package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;

import java.nio.file.Path;

/**
 * The working-copy mark, and the name offered at save time.
 *
 * <p>Ticket 004 D7: whether a schematic is already a working copy is decided by the
 * <strong>description marker</strong>, not the filename suffix. {@code schematicFile} is
 * {@code private final} with no setter, so after a save-as {@code getFile()} still reports the
 * original path - keying the check on the filename would offer to fork again on the very next
 * Replace of the same session.
 *
 * <p>This reads {@code getFile()} freely, which is only safe because
 * {@code LitematicaSchematicAccessor} makes a saved schematic learn its new path. Before that,
 * the stale path caused three separate bugs here and in {@link SaveOp}.
 */
public final class Naming {

    public static final String MARKER = "Material-replaced working copy (LetMeReplace)";
    public static final String SUFFIX = " (MaterialReplaced)";

    private static final String EXTENSION = ".litematic";

    private Naming() {
    }

    public static boolean isWorkingCopy(LitematicaSchematic schematic) {
        String description = schematic.getMetadata().getDescription();
        return description != null && description.contains(MARKER);
    }

    /** Idempotent: a schematic already carrying the mark is left exactly as it is. */
    public static void mark(SchematicMetadata metadata) {
        String description = metadata.getDescription();

        if (description != null && description.contains(MARKER)) {
            return;
        }

        metadata.setDescription(description == null || description.isBlank()
                ? MARKER
                : description + "\n" + MARKER);
    }

    /**
     * The name pre-filled into the save field. A schematic that is already a working copy keeps
     * its name and is edited in place; anything else is offered the tagged fork. A schematic with
     * no file at all - built from a world selection - has no original to protect, so it keeps its
     * own name too.
     */
    public static String suggestedName(LitematicaSchematic schematic) {
        String base = baseName(schematic);

        if (isWorkingCopy(schematic) || schematic.getFile() == null) {
            return base;
        }

        return base.endsWith(SUFFIX) ? base : base + SUFFIX;
    }

    private static String baseName(LitematicaSchematic schematic) {
        Path file = schematic.getFile();

        if (file != null) {
            String name = file.getFileName().toString();
            return name.endsWith(EXTENSION)
                    ? name.substring(0, name.length() - EXTENSION.length())
                    : name;
        }

        String name = schematic.getMetadata().getName();
        return name == null || name.isBlank() ? "schematic" : name;
    }
}
