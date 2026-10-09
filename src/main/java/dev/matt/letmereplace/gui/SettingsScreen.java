package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.History;
import dev.matt.letmereplace.LetMeReplace;
import dev.matt.letmereplace.Settings;
import dev.matt.letmereplace.UndoLog;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

import java.util.List;

/**
 * LetMeReplace's settings, Litematica-style: malilib's own config list, so each hotkey gets the
 * same combination editor, reset button and conflict highlighting as Litematica's - and Undo and
 * Redo as buttons above it, acting on the schematic edited last, the same one the hotkeys use.
 */
public class SettingsScreen extends GuiConfigsBase {

    public SettingsScreen() {
        this(null);
    }

    /** With a parent to go back to - Mod Menu's list, when opened from there. */
    public SettingsScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(10, 52, LetMeReplace.MOD_ID, parent, "LetMeReplace");
        this.useTitleHierarchy = false;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(Settings.HOTKEYS);
    }

    /** One mod, one page: malilib's mod switcher would only list the others. */
    @Override
    protected void buildConfigSwitcher() {
    }

    @Override
    public void initGui() {
        super.initGui();

        LitematicaSchematic target = UndoLog.lastEdited();
        String undone = target == null ? null : UndoLog.label(target);
        String redone = target == null ? null : UndoLog.redoLabel(target);

        ButtonGeneric undo = new ButtonGeneric(10, 26, -1, 20,
                undone == null ? "Nothing to undo" : "Undo: " + undone);
        undo.setEnabled(undone != null);
        this.addButton(undo, (button, mouseButton) -> {
            History.undo(target);
            this.initGui();
        });

        ButtonGeneric redo = new ButtonGeneric(14 + undo.getWidth(), 26, -1, 20,
                redone == null ? "Nothing to redo" : "Redo: " + redone);
        redo.setEnabled(redone != null);
        this.addButton(redo, (button, mouseButton) -> {
            History.redo(target);
            this.initGui();
        });
    }

    @Override
    public void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(context, mouseX, mouseY, partialTicks);

        LitematicaSchematic target = UndoLog.lastEdited();
        String on = target == null ? "No schematic edited yet"
                : "Undo and redo act on " + target.getMetadata().getName();
        this.drawString(context, on, this.width - 10 - this.getStringWidth(on), 32, Ui.GREY);
    }
}
