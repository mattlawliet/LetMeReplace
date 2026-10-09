package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Share;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

import java.nio.file.Path;

/**
 * Stopping a share, from a click on the green share icon. A member stops syncing; the owner ends
 * it for everyone. Either way every copy stays as an ordinary schematic (map, rounds 1 and 2), so
 * the one thing this screen has to say is what is lost: for the owner, the shared history.
 */
public class ShareStopScreen extends GuiBase {

    private final LitematicaSchematic schematic;
    private final boolean owner;

    public ShareStopScreen(LitematicaSchematic schematic) {
        this.schematic = schematic;
        this.owner = Share.ownedHere(schematic);
        this.useTitleHierarchy = false;
        // Named, because with two shares open the material list you clicked from is easy to mix up
        // (reported 2026-10-07: a stop meant for one share landed on the other).
        this.setTitle((owner ? "End the share of " : "Stop syncing ") + Share.displayName(schematic) + "?");
    }

    @Override
    public void initGui() {
        super.initGui();

        int y = boxTop() + boxHeight() - 26;
        int centreX = this.width / 2;

        this.addButton(new ButtonGeneric(centreX - 124, y, 120, 20, owner ? "End for everyone" : "Stop syncing"),
                this::onStop);
        this.addButton(new ButtonGeneric(centreX + 4, y, 120, 20, "Keep syncing"),
                (button, mouseButton) -> this.closeGui(true));
    }

    private void onStop(ButtonBase button, int mouseButton) {
        Path kept = owner ? Share.end(schematic) : Share.leave(schematic);

        if (kept == null) {
            this.addMessage(Message.MessageType.ERROR, "Could not take it out of lmr_sync - see the log");
            return;
        }

        this.closeGui(true);
    }

    private int boxWidth() {
        return Math.min(320, this.width - 40);
    }

    private int boxHeight() {
        return 100;
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

        String first = owner
                ? "Everyone keeps the version they have now,"
                : "You keep the version you have now";
        String second = owner
                ? "and the shared history is deleted."
                : "as an ordinary schematic. Others keep syncing.";
        this.drawString(context, first, boxLeft() + 8, boxTop() + 28, Ui.GREY);
        this.drawString(context, second, boxLeft() + 8, boxTop() + 40, Ui.GREY);
    }
}
