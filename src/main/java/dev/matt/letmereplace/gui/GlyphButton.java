package dev.matt.letmereplace.gui;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

/**
 * A small square button that paints its own glyph out of rectangles.
 *
 * <p>The first version of this shipped a 256x256 icon sheet and an {@code IGuiIcon}. The
 * remove-layers icon never appeared in game, and the texture was not the obvious suspect it looks
 * like: the sheet was in the jar, {@code GuiContext.bindTexture} logs an error for a texture it
 * cannot resolve and logged nothing, the render element carries its own UVs so it cannot collapse
 * the way {@code MaLiLibBlockStateGuiElement} does in ticket 005 P5, and {@code ButtonGeneric}
 * never calls {@code IGuiIcon.renderAt} at all - it reads {@code getU}/{@code getV}/
 * {@code getTexture} and draws the rect itself.
 *
 * <p>Rather than keep paying to find out which layer of that swallowed the draw, the texture is
 * gone. Both glyphs are a handful of rectangles, and {@code RenderUtils.drawRect} is the call
 * {@link ConfirmScreen} already paints its panel with, so it is known to work on this malilib.
 * No asset, no sheet, no generator script, and nothing left to resolve at runtime.
 */
public class GlyphButton extends ButtonGeneric {

    public enum Glyph {
        /** A floppy disk: body, shutter, label. */
        SAVE,
        /** Three stacked layers with the top one lifted clear and reddened. */
        REMOVE_LAYERS,
        /** A painter's palette: board, thumb hole, four blobs of colour. */
        PALETTE,
        /** Three posts of one height standing on a baseline - all facing the same way. */
        NORMALIZE,
        /** A solid box with a dashed copy of itself beside it - stack and move. */
        BOX,
        /** Three linked nodes, one on the left feeding two on the right - share. */
        SHARE,
        /** The share nodes in green - this schematic is shared and syncing. */
        SHARED
    }

    public static final int SIZE = 16;

    private static final int OUTLINE = 0xFF222222;
    private static final int BODY = 0xFFC6C6C6;
    private static final int SHUTTER = 0xFF6E6E6E;
    private static final int LABEL = 0xFFF0F0F0;
    private static final int CUT = 0xFFC44A3E;
    private static final int BOARD = 0xFFB98A54;
    private static final int PAINT_RED = 0xFFC44A3E;
    private static final int PAINT_BLUE = 0xFF4A7FC4;
    private static final int PAINT_GREEN = 0xFF5FA85A;
    private static final int PAINT_YELLOW = 0xFFD8C24A;
    private static final int SYNCED = 0xFF7ED87E;

    private final Glyph glyph;

    public GlyphButton(int x, int y, Glyph glyph, String... hoverStrings) {
        super(x, y, SIZE, SIZE, "", hoverStrings);
        this.glyph = glyph;
        this.setRenderDefaultBackground(false);
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
        super.render(context, mouseX, mouseY, selected);

        int x = this.getX();
        int y = this.getY();

        switch (glyph) {
            case SAVE -> renderSave(context, x, y);
            case REMOVE_LAYERS -> renderRemoveLayers(context, x, y);
            case PALETTE -> renderPalette(context, x, y);
            case NORMALIZE -> renderNormalize(context, x, y);
            case BOX -> renderBox(context, x, y);
            case SHARE -> renderShare(context, x, y, BODY);
            case SHARED -> renderShare(context, x, y, SYNCED);
        }
    }

    private void renderSave(GuiContext context, int x, int y) {
        // Outline, then the body inset inside it, so the disk reads as an object rather than a
        // grey square - at 16px the border is most of what makes it legible.
        RenderUtils.drawRect(context, x + 1, y + 1, 14, 14, OUTLINE);
        RenderUtils.drawRect(context, x + 2, y + 2, 12, 12, BODY);

        // Shutter across the top, label across the bottom: the two features that say "floppy".
        RenderUtils.drawRect(context, x + 5, y + 2, 6, 5, SHUTTER);
        RenderUtils.drawRect(context, x + 7, y + 3, 2, 3, OUTLINE);

        RenderUtils.drawRect(context, x + 4, y + 9, 8, 5, LABEL);
        RenderUtils.drawRect(context, x + 5, y + 10, 6, 1, OUTLINE);
        RenderUtils.drawRect(context, x + 5, y + 12, 6, 1, OUTLINE);
    }

    private void renderPalette(GuiContext context, int x, int y) {
        // A rounded board, faked the way the disk is: an outline rect with the body inset, then
        // the corners bitten off by a second, narrower pair of rows.
        RenderUtils.drawRect(context, x + 1, y + 3, 14, 10, OUTLINE);
        RenderUtils.drawRect(context, x + 2, y + 2, 12, 12, OUTLINE);
        RenderUtils.drawRect(context, x + 2, y + 4, 12, 8, BOARD);
        RenderUtils.drawRect(context, x + 3, y + 3, 10, 10, BOARD);

        // The thumb hole, which is the one feature that says palette rather than plank.
        RenderUtils.drawRect(context, x + 9, y + 8, 3, 3, OUTLINE);

        RenderUtils.drawRect(context, x + 4, y + 5, 2, 2, PAINT_RED);
        RenderUtils.drawRect(context, x + 7, y + 4, 2, 2, PAINT_BLUE);
        RenderUtils.drawRect(context, x + 10, y + 5, 2, 2, PAINT_YELLOW);
        RenderUtils.drawRect(context, x + 4, y + 9, 2, 2, PAINT_GREEN);
    }

    private void renderNormalize(GuiContext context, int x, int y) {
        // Three posts of the same height on one baseline. The grain line down each one is what
        // makes them read as blocks standing the same way up rather than as a bar chart.
        RenderUtils.drawRect(context, x + 2, y + 13, 12, 1, OUTLINE);

        for (int post = 0; post < 3; ++post) {
            int left = x + 3 + post * 4;

            RenderUtils.drawRect(context, left, y + 3, 3, 10, BODY);
            RenderUtils.drawRect(context, left + 1, y + 4, 1, 8, OUTLINE);
        }
    }

    private void renderBox(GuiContext context, int x, int y) {
        // The tile: outlined and filled, like the disk.
        RenderUtils.drawRect(context, x + 1, y + 4, 7, 8, OUTLINE);
        RenderUtils.drawRect(context, x + 2, y + 5, 5, 6, BODY);

        // Its copy, dashed, so it reads as "one more of these here" rather than a second block.
        for (int i = 0; i < 8; i += 2) {
            RenderUtils.drawRect(context, x + 8 + i % 7, y + 4, 1, 1, BODY);
            RenderUtils.drawRect(context, x + 8 + i % 7, y + 11, 1, 1, BODY);
            RenderUtils.drawRect(context, x + 14, y + 4 + i, 1, 1, BODY);
        }
    }

    private void renderShare(GuiContext context, int x, int y, int colour) {
        // The two links first, as stepped diagonals, so the nodes draw over their ends.
        for (int i = 0; i < 7; ++i) {
            RenderUtils.drawRect(context, x + 4 + i, y + 7 - i * 4 / 7, 1, 1, colour);
            RenderUtils.drawRect(context, x + 4 + i, y + 8 + i * 4 / 7, 1, 1, colour);
        }

        // Nodes, outlined like the disk so they stay legible at 16px.
        int[][] nodes = {{1, 6}, {10, 1}, {10, 11}};

        for (int[] node : nodes) {
            RenderUtils.drawRect(context, x + node[0], y + node[1], 5, 5, OUTLINE);
            RenderUtils.drawRect(context, x + node[0] + 1, y + node[1] + 1, 3, 3, colour);
        }
    }

    private void renderRemoveLayers(GuiContext context, int x, int y) {
        // The top layer is the one going: red, and lifted a row clear of the two that stay.
        RenderUtils.drawRect(context, x + 3, y + 1, 10, 3, CUT);

        RenderUtils.drawRect(context, x + 2, y + 6, 12, 3, BODY);
        RenderUtils.drawRect(context, x + 3, y + 7, 10, 1, OUTLINE);

        RenderUtils.drawRect(context, x + 2, y + 11, 12, 3, BODY);
        RenderUtils.drawRect(context, x + 3, y + 12, 10, 1, OUTLINE);
    }
}
