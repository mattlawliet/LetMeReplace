package dev.matt.letmereplace.gui;

/** Shared colours and metrics, so the three screens read as one mod. */
final class Ui {

    static final int MARGIN = 16;
    static final int TITLE_H = 16;
    static final int SEARCH_H = 20;
    static final int FOOTER_H = 14;

    /** Widest a full-screen panel gets; past it the panel centres instead of stretching. */
    static final int MAX_W = 760;

    static final int SHADE = 0xC0101014;
    static final int PANEL = 0xF0181820;
    static final int PANEL_ALT = 0xF0202028;
    static final int HEADER_BG = 0xF02A2A34;
    static final int EDGE = 0xFF3A3A46;
    static final int ROW_ODD = 0x30FFFFFF;
    static final int ROW_EVEN = 0x18FFFFFF;
    static final int ROW_SEL = 0x605A8CC8;

    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFF9A9AA6;
    static final int GOLD = 0xFFFFD24A;
    static final int GREEN = 0xFF7ED87E;
    static final int RED = 0xFFE86A6A;
    static final int AQUA = 0xFF7FD8E8;
    static final int LABEL = 0xFFE0E0E0;
    static final int WELL = 0xFF000000;
    /** Laid over a glyph to say it is disabled. */
    static final int DIM = 0xA0101014;

    static final int SELECTED = AQUA;

    /** Left edge of a full-screen panel: the margin, or centred once the screen is wider than MAX_W. */
    static int left(int screenWidth) {
        return Math.max(MARGIN, (screenWidth - MAX_W) / 2);
    }

    /** The 1px ring that marks the chosen button in a column of choices. */
    static void ring(fi.dy.masa.malilib.render.GuiContext context, int[] box) {
        if (box != null) {
            fi.dy.masa.malilib.render.RenderUtils.drawOutline(context, box[0] - 1, box[1] - 1,
                    box[2] + 2, box[3] + 2, SELECTED);
        }
    }

    private Ui() {
    }
}
