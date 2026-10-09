package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;

import java.lang.ref.WeakReference;

/**
 * The layer selection a replace is confined to, when there is one.
 *
 * <p>Asked for at the keyboard: the layers screen already selects a set of layers and shows it,
 * and a cut is not the only thing worth doing to a selection. "Replace in these layers" hands
 * that same selection to the ordinary Replace path instead of deleting it, so a row swap or a
 * whole palette change lands on one floor of a build rather than all of them.
 *
 * <p>ponytail: ambient rather than threaded through every signature. One material list is open at
 * a time and there is one selection in play, so {@link Census} and {@link Swap} read it where
 * they already walk the containers - which is four call sites instead of a parameter added to
 * seven methods, two records and three screens. If a second editing context ever exists, this
 * becomes a field on {@link Session} and the compiler names every place that has to change.
 *
 * <p>Held weakly and checked by identity, so an unloaded schematic neither stays alive nor leaves
 * a selection behind that would silently confine a replace on the next one.
 */
public final class Scope {

    /** No synchronisation: set from a button and read from the same client thread that walks. */
    private static WeakReference<LitematicaSchematic> owner;

    private static Layers.Mask mask;

    private static String label;

    private Scope() {
    }

    public static void set(LitematicaSchematic schematic, Layers.Mask mask, String label) {
        Scope.owner = new WeakReference<>(schematic);
        Scope.mask = mask;
        Scope.label = label;

        LetMeReplace.LOGGER.info("replace scope set to layers {}", label);
    }

    public static void clear() {
        if (mask != null) {
            LetMeReplace.LOGGER.info("replace scope cleared (was layers {})", label);
        }

        owner = null;
        mask = null;
        label = null;
    }

    public static boolean isActive(LitematicaSchematic schematic) {
        return mask != null && owner.get() == schematic;
    }

    /** What the selection was, for the button that says so and the confirm that repeats it. */
    public static String label() {
        return label == null ? "" : label;
    }

    /**
     * Whether a position may be changed. True whenever no selection is in force, so every caller
     * is the unscoped one by default and forgetting the check fails open rather than silently
     * refusing to replace anything.
     */
    public static boolean covers(LitematicaSchematic schematic,
                                 String region, int x, int y, int z) {
        if (mask == null) {
            return true;
        }

        LitematicaSchematic held = owner.get();

        if (held == null) {
            clear();                    // the schematic it belonged to is gone
            return true;
        }

        return held != schematic || mask.covers(region, x, y, z);
    }
}
