package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Candidate;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One picker row: the <em>result</em>, its name, and a warning when it would leave positions
 * behind.
 *
 * <p>Ticket 005 P5 - the icon renders the source's state carried onto this candidate, not the
 * candidate's default, because a preview of a default is a preview of the wrong thing. P7 - the
 * converts-N-of-M count appears only on rows that would skip something, the same way 004's
 * confirm screen hides its zeros.
 */
public class WidgetCandidate extends WidgetListEntryBase<Candidate> {

    private final boolean isOdd;
    private final BlockState preview;
    private final int replaced;
    private final int skipped;

    public WidgetCandidate(int x, int y, int width, int height, boolean isOdd,
                           Candidate entry, int listIndex, PickerScreen screen) {
        super(x, y, width, height, entry, listIndex);
        this.isOdd = isOdd;
        this.preview = screen.previewOf(entry);

        int[] counts = screen.countsFor(entry);
        this.replaced = counts[0];
        this.skipped = counts[1];
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
        RenderUtils.drawRect(context, this.x, this.y, this.width, this.height,
                selected ? Ui.ROW_SEL : (isOdd ? Ui.ROW_ODD : Ui.ROW_EVEN));

        Candidate candidate = this.getEntry();
        int textY = this.y + (this.height - 8) / 2 + 1;

        drawIcon(context, candidate, this.x + 3, this.y + 1);

        int nameColour = candidate.isDelete() ? Ui.RED : Ui.WHITE;
        this.drawString(context, this.x + 22, textY, nameColour,
                trim(candidate.displayName(), this.width - 90));

        // P7: silence on a clean target, a count only when this one would leave work behind.
        if (skipped > 0) {
            String note = replaced + "/" + (replaced + skipped);
            this.drawString(context, this.x + this.width - 6 - this.getStringWidth(note), textY,
                    replaced == 0 ? Ui.RED : Ui.GOLD, note);
        }
    }

    private void drawIcon(GuiContext context, Candidate candidate, int x, int y) {
        if (candidate.isDelete()) {
            RenderUtils.drawRect(context, x + 4, y + 4, 8, 8, Ui.RED);
            return;
        }

        // Item icons, not block models. `RenderUtils.renderModelInGui` submits a
        // PictureInPictureRenderState, which is the machinery vanilla uses for the player model
        // in the inventory - built for one element on screen, not one per row. Its renderer
        // reports a constant texture label ("malilib:block_model"), so a listful of them shared
        // one target and every row drew the same block; and each element is bounded to
        // (x0, y0, x0 + size, y0 + size) while yOffset lifts the model above y0, which clipped
        // the tops off. Both were reported from the instance on 2026-09-09.
        //
        // The carried-result render still happens, once, in the picker's preview panel - which
        // ticket 005 P8 already called the place where a state is actually legible.
        if (!candidate.icon().isEmpty()) {
            InventoryOverlay.renderStackAt(context, candidate.icon(), x, y, 0.75f);
            return;
        }

        RenderUtils.drawRect(context, x + 4, y + 4, 8, 8, Ui.GREY);
    }

    private String trim(String text, int maxWidth) {
        String out = text;

        while (out.length() > 1 && this.getStringWidth(out) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }

        return out;
    }

    /** Lowercase: malilib lowercases the typed query but compares with a bare contains(). */
    public static java.util.List<String> filterStrings(Candidate candidate) {
        return java.util.List.of(candidate.normalisedName(), candidate.normalisedId());
    }
}
