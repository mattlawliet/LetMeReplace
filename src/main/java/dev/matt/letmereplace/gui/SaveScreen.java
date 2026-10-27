package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Bounds;
import dev.matt.letmereplace.Naming;
import dev.matt.letmereplace.Pending;
import dev.matt.letmereplace.SaveOp;
import dev.matt.letmereplace.UndoLog;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfirmAction;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

import java.nio.file.Path;

/**
 * Name the working copy and write it.
 *
 * <p>One screen rather than two (ticket 004 D13), because {@code GuiTextInput} cannot carry the
 * side-by-side checkbox and adding a step to this flow is exactly what the screen-flow ticket
 * exists to avoid. Overwrite gets our own confirm defaulting to no (D6), rather than Litematica's
 * transient error after the fact.
 */
public class SaveScreen extends GuiBase implements IConfirmationListener {

    private final LitematicaSchematic schematic;
    private final Path originalFile;

    private GuiTextFieldGeneric nameField;
    private boolean placeOriginalBeside;
    private boolean trimToContents;
    private String pendingName;
    private Bounds.Trim trimmed;

    /**
     * Set when the overwrite prompt's OK did the write. It cannot close this screen itself:
     * {@code GuiConfirmAction}'s button listener calls the listener and <em>then</em>
     * unconditionally reopens its own parent, which is this screen - so anything we close from
     * inside {@code onActionConfirmed} is immediately undone. Closing happens when control comes
     * back here instead.
     */
    private boolean savedViaPrompt;

    public SaveScreen(LitematicaSchematic schematic) {
        this.schematic = schematic;
        this.originalFile = schematic.getFile();
        this.useTitleHierarchy = false;
        this.setTitle("Save working copy");
    }

    @Override
    public void initGui() {
        if (savedViaPrompt) {
            savedViaPrompt = false;
            this.closeGui(true);
            return;
        }

        super.initGui();

        String current = nameField != null ? nameField.getValue() : Naming.suggestedName(schematic);

        int x = boxLeft() + 8;
        int y = boxTop() + 30;
        int fieldWidth = boxWidth() - 16;

        this.nameField = new GuiTextFieldGeneric(x, y, fieldWidth, 18, this.font);
        this.nameField.setMaxLength(128);
        this.nameField.setValue(current);
        this.addTextField(this.nameField, null);

        y += 30;

        // ponytail: if this turns out to be set-once, demote it to a config toggle and this
        // whole screen collapses back to malilib's GuiTextInput.
        this.addButton(new CheckButton(x, y, fieldWidth, "Also place the original beside it",
                () -> placeOriginalBeside), (button, mouseButton) -> placeOriginalBeside = !placeOriginalBeside);

        y += 18;

        this.addButton(new CheckButton(x, y, fieldWidth, "Shrink the box to what is left",
                () -> trimToContents), (button, mouseButton) -> trimToContents = !trimToContents);

        y = boxTop() + boxHeight() - 26;
        int centreX = this.width / 2;

        this.addButton(new ButtonGeneric(centreX - 104, y, 100, 20, "Save"), this::onSave);
        this.addButton(new ButtonGeneric(centreX + 4, y, 100, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));
    }

    private void onSave(ButtonBase button, int mouseButton) {
        String name = nameField.getValue().trim();

        if (name.isEmpty()) {
            this.addMessage(fi.dy.masa.malilib.gui.Message.MessageType.ERROR, "Name the file first");
            return;
        }

        if (SaveOp.exists(name)) {
            this.pendingName = name;
            // Our own prompt, defaulting to no, rather than discovering the clash from an error.
            GuiConfirmAction prompt = new GuiConfirmAction(320, "Overwrite?", this, this,
                    name + ".litematic already exists. Overwrite it?");
            GuiBase.openGui(prompt);
            return;
        }

        if (write(name, false)) {
            this.closeGui(true);
        }
    }

    /** Writes and reports whether it happened. Closing is the caller's business - see above. */
    private boolean write(String name, boolean overwrite) {
        // Before the write, so the file gets the tightened box - and only when it was ticked,
        // because it rewrites every region and throws the undo history away.
        if (trimToContents) {
            trimmed = Bounds.tighten(schematic);
        }

        if (!SaveOp.save(schematic, name, overwrite)) {
            // Litematica has already put its own error on screen.
            return false;
        }

        Pending.clear(schematic);
        // Save is the commit point, so undo does not reach across it. Leaving it available would
        // let the schematic drift from the file it was just written to while reporting itself
        // clean, with Save disabled and no way to write the reverted version.
        UndoLog.forget(schematic);

        if (placeOriginalBeside) {
            SaveOp.placeOriginalBeside(schematic, originalFile);
        }

        return true;
    }

    // ------------------------------------------------- overwrite prompt callbacks

    @Override
    public boolean onActionConfirmed() {
        if (pendingName != null) {
            savedViaPrompt = write(pendingName, true);
            pendingName = null;
        }

        return true;
    }

    @Override
    public boolean onActionCancelled() {
        pendingName = null;
        return true;
    }

    // ------------------------------------------------------------------ painting

    private int boxWidth() {
        return Math.min(340, this.width - 40);
    }

    private int boxHeight() {
        return 164;
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
        this.drawString(context, this.getTitleString(), boxLeft() + 8, boxTop() + 4, Ui.WHITE);

        String note = Naming.isWorkingCopy(schematic)
                ? "Already a working copy - edited in place."
                : "The original is never written.";
        this.drawString(context, note, boxLeft() + 8, boxTop() + boxHeight() - 54, Ui.GREY);

        // The trim is the one option with a consequence worth naming before it is ticked: it
        // moves the regions, which resets any per-region rotation on the placements, and it
        // ends the undo history.
        String trimNote = trimmed != null && trimmed.shrank()
                ? "Trimmed " + trimmed.describe()
                : "Shrinking clears undo and resets sub-region placements.";
        this.drawString(context, trimNote, boxLeft() + 8, boxTop() + boxHeight() - 42, Ui.GREY);
    }
}
