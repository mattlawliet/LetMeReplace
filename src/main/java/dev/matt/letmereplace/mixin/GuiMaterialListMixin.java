package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.Normalize;
import dev.matt.letmereplace.Pending;
import dev.matt.letmereplace.Swap;
import dev.matt.letmereplace.UndoLog;
import dev.matt.letmereplace.Schematics;
import dev.matt.letmereplace.Scope;
import dev.matt.letmereplace.Share;
import dev.matt.letmereplace.gui.BoxScreen;
import dev.matt.letmereplace.gui.ConfirmScreen;
import dev.matt.letmereplace.gui.GlyphButton;
import dev.matt.letmereplace.gui.LayersScreen;
import dev.matt.letmereplace.gui.PaletteScreen;
import dev.matt.letmereplace.gui.SaveScreen;
import dev.matt.letmereplace.gui.Screens;
import dev.matt.letmereplace.gui.SharePickScreen;
import dev.matt.letmereplace.gui.ShareStopScreen;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Save button and the dirty indicator (ticket 004 D5).
 *
 * <p>Swaps queue and only one Save happens at the end, so the commit point has to live on the
 * material list itself - saving from each confirm would contradict the map, and sending the user
 * elsewhere in Litematica's UI to commit work started here is worse. D4 lets the list be closed
 * with swaps pending and no prompt, which is only safe because reopening it shows this.
 */
@Mixin(GuiMaterialList.class)
public abstract class GuiMaterialListMixin {

    @Unique
    private static String letmereplace$lastLoggedState = "";

    @Unique
    private static int letmereplace$paletteX;

    /** malilib draws the title at y 6 and the info icon at (width - 23, 10); 16 fits between. */
    @Unique
    private static final int TITLE_ROW_Y = 6;

    @Unique
    private static final int TITLE_ROW_H = 16;

    /** Litematica's title as it was set, before any trim to fit beside our buttons. */
    @Unique
    private String letmereplace$fullTitle;

    /** Where malilib draws the title (GuiBase.drawTitle). */
    @Unique
    private static final int TITLE_X = 20;


    /**
     * Ticket 006 A5, now a square on the title row with the rest of ours.
     *
     * <p>It was a worded button because it was the only thing we had on Litematica's button row,
     * where an icon among words would have looked like a mistake. On our own row the words were
     * what did not fit: a long placement name grew into them, and fitting the label to the gap
     * meant the button changed its name depending on the schematic. A palette is also the one
     * thing in this mod with an obvious picture.
     */
    @Unique
    private static void addPaletteButton(GuiMaterialList gui, LitematicaSchematic schematic,
                                         int x, int y) {
        letmereplace$paletteX = x;

        gui.addButton(new GlyphButton(x, y, GlyphButton.Glyph.PALETTE, "Change palette"),
                (button, mouseButton) ->
                        Screens.open(new PaletteScreen(schematic, gui.getMaterialList()), gui));
    }

    /**
     * One orientation for the blocks whose orientation cannot be seen.
     *
     * <p>No screen of its own: it builds the same {@link Swap.Plan} a Replace builds and hands it
     * to the same confirm, which already names what it is about to touch and counts it. See
     * {@link Normalize} for why this is bark blocks and not logs.
     *
     * <p>The census is taken on the click, not on every {@code initGui}: Litematica re-runs that
     * whenever a count task lands, and a full walk of the schematic to decide whether to grey out
     * a button is a walk nobody asked for. A schematic with nothing to turn reaches a confirm that
     * says so and cannot be confirmed, which is the same answer one screen later.
     */
    @Unique
    private static void addNormalizeButton(GuiMaterialList gui, LitematicaSchematic schematic,
                                           int x, int y) {
        GlyphButton normalize = new GlyphButton(x, y, GlyphButton.Glyph.NORMALIZE,
                "Stand every wood and hyphae block upright",
                "Their bark is the same on all six faces, so the axis only shows in the hand");

        gui.addButton(normalize, (button, mouseButton) -> {
            Screens.open(new ConfirmScreen(Normalize.commit(schematic, gui.getMaterialList())), gui);
        });
    }

    /**
     * One level of undo for the most recent change, leftmost in the bottom glyph group.
     *
     * <p>It used to sit beside the palette button on the top row, where there is no room for it:
     * the multiplier field starts where that row's free space ends, and the arrow was drawn behind
     * it. Icon-only with hover text either way. Neither malilib nor Litematica ships a back or
     * curved arrow, so GlyphButton draws one.
     */
    @Unique
    private static void addUndoButton(GuiMaterialList gui, LitematicaSchematic schematic,
                                      int x, int y) {
        boolean shared = Share.idOf(schematic) != null;
        boolean available = shared || UndoLog.has(schematic);
        int depth = UndoLog.depth(schematic);
        String what = UndoLog.label(schematic);

        // Say how far back it goes: the stack is bounded, so "and 19 more" is the difference
        // between knowing the history is there and assuming it is endless. Shared, the history is
        // the server's, so the button can only say what it will do.
        String hover = shared
                ? "Undo the group's newest edit, whoever made it"
                : !available
                ? "Nothing to undo"
                : depth > 1 ? "Undo: " + what + "  (" + (depth - 1) + " more)" : "Undo: " + what;

        GlyphButton undo = new GlyphButton(x, y, GlyphButton.Glyph.UNDO, hover);
        undo.setEnabled(available);

        gui.addButton(undo, (button, mouseButton) -> {
            int restored = dev.matt.letmereplace.History.undo(schematic);

            if (restored > 0) {
                dev.matt.letmereplace.Recount.later(gui.getMaterialList());
                gui.initGui();
            }
        });
    }

    /**
     * Removing layers, immediately left of Save.
     *
     * <p>It sits with Save rather than with the palette button because it is the same kind of
     * thing: a change to the whole schematic rather than to one material row. The layer numbers
     * are world coordinates, so a placement-backed list is the case that behaves as the render
     * layers menu does - {@link LayersScreen} says so on screen when there is no placement.
     */
    @Unique
    private static void addLayersButton(GuiMaterialList gui, LitematicaSchematic schematic,
                                        MaterialListBase materialList, int x, int y) {
        GlyphButton layers = new GlyphButton(x, y, GlyphButton.Glyph.REMOVE_LAYERS,
                "Remove layers from the schematic");

        gui.addButton(layers, (button, mouseButton) -> Screens.open(
                new LayersScreen(schematic, Schematics.placementBehind(materialList), materialList),
                gui));
    }

    /**
     * The layer selection a Replace is confined to, and the way out of it.
     *
     * <p>A scope that only existed in memory would be the worst kind of state: rows are
     * Litematica's and still count the whole schematic, so a confined swap that said nothing would
     * look like a swap that half worked. A button rather than a drawn line because a button is its
     * own way out - clicking it replaces everywhere again - and because that needs no draw
     * injection into Litematica's screen.
     */
    @Unique
    private static void addScopeButton(GuiMaterialList gui, int x, int y, int width) {
        ButtonGeneric scope = new ButtonGeneric(x, y, width, TITLE_ROW_H,
                "Layers: " + Scope.label());
        scope.setHoverStrings("Replace and Change palette only touch blocks inside these layers",
                "Click to replace everywhere again");

        gui.addButton(scope, (button, mouseButton) -> {
            Scope.clear();
            gui.initGui();
        });
    }

    /**
     * Shift-click only, so a stray click never shares anything (map, "Settled while charting").
     * A plain click does nothing but the hover says how. Already shared, it turns green: a click
     * lets the owner change who it goes to (or end it), and a member stop syncing.
     */
    @Unique
    private static void addShareButton(GuiMaterialList gui, LitematicaSchematic schematic, int x, int y) {
        if (Share.idOf(schematic) != null) {
            gui.addButton(new GlyphButton(x, y, GlyphButton.Glyph.SHARED, "Shared",
                    "Edits sync automatically with everyone who accepted",
                    Share.ownedHere(schematic) ? "Click to change who it's shared with" : "Click to stop syncing"),
                    (button, mouseButton) -> Screens.open(Share.ownedHere(schematic)
                            ? SharePickScreen.edit(schematic) : new ShareStopScreen(schematic), gui));
            return;
        }

        gui.addButton(new GlyphButton(x, y, GlyphButton.Glyph.SHARE, "Share with players on this server",
                "Shift-click to pick who"), (button, mouseButton) -> {
            if (GuiBase.isShiftDown()) {
                Screens.open(new SharePickScreen(schematic,
                        Schematics.placementBehind(gui.getMaterialList())), gui);
            }
        });
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void letmereplace$addSaveButton(CallbackInfo callbackInfo) {
        GuiMaterialList gui = (GuiMaterialList) (Object) this;
        LitematicaSchematic schematic = Schematics.behind(gui.getMaterialList());

        if (schematic == null) {
            return;
        }

        boolean dirty = schematic.getMetadata().wasModifiedSinceSaved();
        int pending = Pending.count(schematic);

        // Icon-only, like the undo arrow beside the palette button: the pending count moved into
        // the hover text when these became squares, which is the one thing a worded button said
        // that an icon cannot.
        String saveHover = !dirty
                ? "No swaps to save yet"
                : pending > 0
                        ? "Save working copy  (" + pending + " pending)"
                        : "Save working copy";

        // One group on the title row, laid out right to left from the info icon.
        //
        // That row is the only empty space on this screen that is ours to use: the title sits at
        // the left end of it and malilib's info icon at the right, at (width - 23, 10), and
        // nothing is drawn between them. Everywhere else is Litematica's - the button row ends in
        // a text field, and the footer holds the progress label, a second button row and the menu
        // button, which is where the four overlaps reported from the instance came from.
        int y = TITLE_ROW_Y;
        int step = GlyphButton.SIZE + 4;

        int x = gui.getScreenWidth() - 29 - GlyphButton.SIZE;        // 6px clear of the icon

        GlyphButton save = new GlyphButton(x, y, GlyphButton.Glyph.SAVE, saveHover);
        save.setEnabled(dirty);
        gui.addButton(save, (button, mouseButton) -> Screens.open(new SaveScreen(schematic), gui));

        int saveX = x;

        // Only on a server whose plugin said hello - nowhere to share to otherwise.
        if (Share.available()) {
            x -= step;
            addShareButton(gui, schematic, x, y);
        }

        x -= step;
        addLayersButton(gui, schematic, gui.getMaterialList(), x, y);

        x -= step;
        GlyphButton box = new GlyphButton(x, y, GlyphButton.Glyph.BOX,
                "Stack or move a box inside the schematic",
                "The box is Litematica's selected area selection box");
        gui.addButton(box, (button, mouseButton) -> Screens.open(new BoxScreen(schematic,
                Schematics.placementBehind(gui.getMaterialList()), gui.getMaterialList()), gui));

        x -= step;
        addUndoButton(gui, schematic, x, y);

        x -= step;
        addNormalizeButton(gui, schematic, x, y);

        x -= step;
        addPaletteButton(gui, schematic, x, y);

        if (Scope.isActive(schematic)) {
            // The one worded button left, and it stays worded: a layer selection that confines
            // every Replace has to say which layers, and no picture says "y 154".
            int width = gui.getStringWidth("Layers: " + Scope.label()) + 20;

            x -= 4 + width;
            addScopeButton(gui, x, y, width);
        }

        // A long placement name ran under the group. The title is the one thing on the row that
        // can give, so it is cut to the space left - from the full title each time, so a wider
        // window gets the words back.
        if (letmereplace$fullTitle == null) {
            letmereplace$fullTitle = gui.getTitleString();
        }

        int room = x - 8 - TITLE_X;
        String title = letmereplace$fullTitle;

        if (gui.getStringWidth(title) > room) {
            while (title.length() > 1 && gui.getStringWidth(title + "...") > room) {
                title = title.substring(0, title.length() - 1);
            }

            title = title.stripTrailing() + "...";
        }

        gui.setTitle(title);

        // After the button exists, not before: logging x while it still held its previous value
        // reported 0 on every first open, which reads as "off-screen" and sent me chasing a
        // placement bug that was not there. Only on a change - Litematica re-runs initGui
        // whenever a count task lands, which otherwise logs every state twice.
        String state = pending + "/" + dirty + "/" + x + "/" + letmereplace$paletteX;

        if (!state.equals(letmereplace$lastLoggedState)) {
            letmereplace$lastLoggedState = state;
            dev.matt.letmereplace.LetMeReplace.LOGGER.info(
                    "material list open: {} pending, dirty={}, group from x={}, save at x={},"
                            + " row y={}, palette at x={} (screen {}x{})",
                    pending, dirty, x, saveX, y, letmereplace$paletteX,
                    gui.getScreenWidth(), gui.getScreenHeight());
        }
    }
}
