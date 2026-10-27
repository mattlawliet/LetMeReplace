package dev.matt.letmereplace.gui;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

import java.util.function.BooleanSupplier;

/**
 * A checkbox: a box and its label, the whole row the click target. malilib 0.29 ships no
 * checkbox icon, so the box is drawn from rectangles the way {@link GlyphButton} draws its glyphs.
 * The state lives with the caller and is read on every frame, so a click handler only flips it.
 */
class CheckButton extends ButtonGeneric {

    private final BooleanSupplier checked;

    CheckButton(int x, int y, int width, String label, BooleanSupplier checked) {
        super(x, y, width, 14, label);
        this.checked = checked;
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
        boolean hover = this.isMouseOver(mouseX, mouseY);
        int x = this.getX();
        int y = this.getY() + 2;

        RenderUtils.drawOutline(context, x, y, 10, 10, hover ? Ui.WHITE : Ui.GREY);
        RenderUtils.drawRect(context, x + 1, y + 1, 8, 8, Ui.WELL);

        if (checked.getAsBoolean()) {
            RenderUtils.drawRect(context, x + 2, y + 2, 6, 6, Ui.SELECTED);
        }

        this.drawString(context, x + 16, y + 1, hover ? Ui.WHITE : Ui.LABEL, this.displayString);
    }
}
