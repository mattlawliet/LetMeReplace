package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * How many swaps are queued against a schematic, for the material list's dirty indicator.
 *
 * <p>Ticket 004 D4 keeps pending swaps live in memory with no prompt on close, which is only
 * safe because reopening the list shows immediately that work is pending (D5). Litematica's
 * {@code wasModifiedSinceSaved()} is the authority on <em>whether</em> anything is pending; this
 * only counts how many Replaces got us there.
 *
 * <p>Weak keys: an unloaded schematic must not be held alive by a counter.
 */
public final class Pending {

    private static final Map<LitematicaSchematic, Integer> COUNTS = new WeakHashMap<>();

    private Pending() {
    }

    public static synchronized void recordSwap(LitematicaSchematic schematic) {
        COUNTS.merge(schematic, 1, Integer::sum);
    }

    /**
     * One change taken back. When the count reaches zero the schematic is back at the state it
     * was last saved in, so the dirty flag goes with it - otherwise Save would stay lit with
     * nothing left to write.
     */
    public static synchronized void undoSwap(LitematicaSchematic schematic) {
        int remaining = COUNTS.getOrDefault(schematic, 0) - 1;

        if (remaining > 0) {
            COUNTS.put(schematic, remaining);
            return;
        }

        COUNTS.remove(schematic);

        // Only genuinely back at the saved state when the whole history has been walked back.
        // The stack is bounded, so an old change can fall off it while its swap is still counted
        // here - clearing the flag then would leave real edits looking saved.
        if (!UndoLog.has(schematic)) {
            schematic.getMetadata().clearModifiedSinceSaved();
        }
    }

    public static synchronized void clear(LitematicaSchematic schematic) {
        COUNTS.remove(schematic);
    }

    public static synchronized int count(LitematicaSchematic schematic) {
        return COUNTS.getOrDefault(schematic, 0);
    }
}
