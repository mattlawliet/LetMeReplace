package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.LetMeReplace;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.gui.screens.Screen;

/**
 * The one navigation rule, in one place.
 *
 * <p>Ticket 004 D1: every LetMeReplace screen's parent is the <strong>material list</strong>,
 * never another LetMeReplace screen. That keeps the chain one deep, makes Cancel and Escape land
 * on the material list from any step rather than on a half-open picker, and makes a stacking bug
 * structurally impossible instead of merely unlikely.
 *
 * <p>D9 asks for it enforced rather than remembered, so {@link #open} throws instead of trusting
 * the convention. malilib's own {@code setParent} only guards against a parent of the same class,
 * which stops a screen parenting itself and does nothing about the alternating chain LMR is in.
 */
public final class Screens {

    private static int opened;

    private Screens() {
    }

    static boolean isOurs(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("dev.matt.letmereplace.gui.");
    }

    /**
     * Opens {@code next} with the material list as its parent. {@code materialList} must be
     * Litematica's screen - pass {@code getParent()} when opening from one of our own screens,
     * never {@code this}.
     */
    public static void open(GuiBase next, Screen materialList) {
        if (isOurs(materialList)) {
            throw new IllegalStateException(
                    "LetMeReplace screens must parent to the material list, not to each other: "
                            + materialList.getClass().getSimpleName() + " -> "
                            + next.getClass().getSimpleName());
        }

        ++opened;
        // INFO, not debug: this counter is the evidence trail for ticket 004's ten-loop walk,
        // and a line nobody can see at the default log level proves nothing. Low volume - a
        // handful per swap.
        LetMeReplace.LOGGER.info("screen open #{}: {} (parent {})", opened,
                next.getClass().getSimpleName(),
                materialList == null ? "none" : materialList.getClass().getSimpleName());

        next.setParent(materialList);
        GuiBase.openGui(next);
    }

    /** How many screens this session has opened - the counter behind D9's ten-loop walk. */
    public static int openedCount() {
        return opened;
    }
}
