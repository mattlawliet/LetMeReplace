package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Commit;
import dev.matt.letmereplace.Scope;
import dev.matt.letmereplace.Pending;
import dev.matt.letmereplace.Slabs;
import dev.matt.letmereplace.Swap;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

/**
 * The last point before anything is mutated.
 *
 * <p>Ticket 004 D11 - the counts come from a dry run, so they are true before the fact. D12 - this
 * screen always shows, even when nothing would be lost, because a Replace button that takes one
 * click sometimes and two other times is a worse surprise than the extra click. D3 - there is no
 * undo, so this is the only safety net.
 */
public class ConfirmScreen extends GuiBase {

    private final Commit commit;
    private final Swap.Plan plan;

    /**
     * Remembered for the session, like the layers screen's last selection: a floor laid with top
     * slabs is usually followed by another, and re-choosing every time is the whole cost of the
     * choice. The button always says which half is chosen, so it is never a hidden setting.
     */
    private static Slabs.Side slabSide = Slabs.Side.KEEP;

    public ConfirmScreen(Commit commit) {
        this.commit = commit;
        this.plan = commit.plan();
        this.useTitleHierarchy = false;
        this.setTitle(commit.title());
    }

    @Override
    public void initGui() {
        super.initGui();

        int centreX = this.width / 2;
        int y = boxTop() + boxHeight() - 26;

        ButtonGeneric confirm = new ButtonGeneric(centreX - 104, y, 100, 20,
                plan.convertsNothing() ? "Nothing to replace" : "Replace " + plan.replaced());
        confirm.setEnabled(!plan.convertsNothing());
        this.addButton(confirm, this::onConfirm);

        this.addButton(new ButtonGeneric(centreX + 4, y, 100, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));

        addSlabButton(y - 24);
    }

    /**
     * Which half the slabs land on, above the confirm row and only when slabs are being made.
     *
     * <p>Here rather than on the picker because this is the screen both gestures pass through - a
     * row Replace and a palette change of twelve slab pairs get the same button - and because the
     * choice changes no count, so nothing on this screen has to be recomputed when it is pressed.
     */
    private void addSlabButton(int y) {
        if (!Slabs.writesSlabs(plan)) {
            return;
        }

        int width = boxWidth() - 16;
        ButtonGeneric slabs = new ButtonGeneric(boxLeft() + 8, y, width, 20, slabSide.label);
        slabs.setHoverStrings(slabSide.hover, "Double slabs are left as they are");

        this.addButton(slabs, (button, mouseButton) -> {
            slabSide = slabSide.next();
            this.initGui();
        });
    }

    private void onConfirm(ButtonBase button, int mouseButton) {
        int written = Swap.apply(commit.schematic(), Slabs.applied(plan, slabSide), commit.title());

        if (written > 0) {
            // A9: one queued swap whichever gesture produced it. A palette change is up to 27
            // block pairs but one action, and "Save (27 pending)" after one click reads as a bug.
            Pending.recordSwap(commit.schematic());
            // Ticket 003: public API, no mixin and no cache invalidation hack. Refresh the model
            // first so the material list rebuilds its rows with the new counts when we return.
            dev.matt.letmereplace.Recount.later(commit.materialList());
        }

        // D2: back to the same GuiMaterialList instance, never a fresh one.
        this.closeGui(true);
    }

    private int boxWidth() {
        return Math.min(340, this.width - 40);
    }

    private int boxHeight() {
        return 150 + commit.notes().size() * 10 + (scoped() ? 12 : 0)
                + (Slabs.writesSlabs(plan) ? 24 : 0);
    }

    /** Whether a layer selection is confining this swap - both gestures reach the same confirm. */
    private boolean scoped() {
        return Scope.isActive(commit.schematic());
    }

    private int boxLeft() {
        return (this.width - boxWidth()) / 2;
    }

    private int boxTop() {
        return (this.height - boxHeight()) / 2;
    }

    @Override
    protected void drawScreenBackground(GuiContext context, int mouseX, int mouseY) {
        RenderUtils.drawRect(context, 0, 0, this.width, this.height, Ui.SHADE);
        RenderUtils.drawRect(context, boxLeft(), boxTop(), boxWidth(), boxHeight(), Ui.PANEL);
        RenderUtils.drawOutline(context, boxLeft() - 1, boxTop() - 1, boxWidth() + 2,
                boxHeight() + 2, Ui.EDGE);
        RenderUtils.drawRect(context, boxLeft(), boxTop(), boxWidth(), Ui.TITLE_H, Ui.HEADER_BG);
    }

    @Override
    protected void drawTitle(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        int x = boxLeft() + 8;
        int y = boxTop() + 4;

        this.drawString(context, trim(this.getTitleString(), boxWidth() - 16), x, y, Ui.WHITE);
        y += 22;

        // Said here rather than only on the material list, because this is the screen the counts
        // are read off: "replaced 42" against a row that says 1,240 is only honest with this line.
        if (scoped()) {
            this.drawString(context, "inside layers " + Scope.label() + " only", x, y, Ui.AQUA);
            y += 12;
        }

        this.drawString(context, "replaced", x, y, Ui.GREY);
        this.drawString(context, String.valueOf(plan.replaced()), x + 120, y, Ui.GREEN);
        y += 12;

        // D-note: the other three counts appear only when non-zero. A permanent row of zeros
        // trains you to stop reading it, which is the opposite of what a warning is for.
        if (plan.skipped() > 0) {
            this.drawString(context, "skipped, no legal state", x, y, Ui.GREY);
            this.drawString(context, String.valueOf(plan.skipped()), x + 120, y, Ui.GOLD);
            y += 12;
        }

        if (plan.waterDropped() > 0) {
            this.drawString(context, "waterlogging lost", x, y, Ui.GREY);
            this.drawString(context, String.valueOf(plan.waterDropped()), x + 120, y, Ui.RED);
            y += 12;
        }

        if (plan.nbtDropped() > 0) {
            this.drawString(context, "block entities emptied", x, y, Ui.GREY);
            this.drawString(context, String.valueOf(plan.nbtDropped()), x + 120, y, Ui.RED);
            y += 12;
        }

        // A8: the skipped members are a handful of block ids, and naming them is the whole
        // decision - "converts 1,240, skips 3" is useless if those 3 are the doors.
        for (String note : commit.notes()) {
            this.drawString(context, trim(note, boxWidth() - 16), x, y, Ui.GOLD);
            y += 10;
        }

        y += 6;

        if (plan.convertsNothing()) {
            this.drawString(context, "No position can take this block.", x, y, Ui.RED);
        } else if (plan.isLossless()) {
            this.drawString(context, "Nothing is lost.", x, y, Ui.GREEN);
        } else {
            this.drawString(context, "This cannot be undone - save is the commit point.", x, y,
                    Ui.GOLD);
        }
    }

    private String trim(String text, int maxWidth) {
        String out = text;

        while (out.length() > 1 && this.getStringWidth(out) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }

        return out;
    }
}
