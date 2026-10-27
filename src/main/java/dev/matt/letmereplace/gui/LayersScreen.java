package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Layers;
import dev.matt.letmereplace.LetMeReplace;
import dev.matt.letmereplace.Pending;
import dev.matt.letmereplace.Scope;
import dev.matt.letmereplace.UndoLog;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiRenderLayerEditBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.LayerMode;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Removing layers from the schematic, with Litematica's own render-layer controls.
 *
 * <p>Subclassing {@link GuiRenderLayerEditBase} is the entire point: the mode button with its
 * four selecting modes, the axis button, the range fields and their adjust arrows are the ones
 * from the render layers menu, not lookalikes, so the numbers here mean exactly what the numbers
 * there mean. Set the view up until you are looking at what you want gone, then cut it.
 *
 * <p>The range is a <strong>copy</strong> of the live render range, seeded from it so the common
 * case is open-and-cut, but edited separately - changing what you are about to delete should not
 * silently change what you can see.
 *
 * <p>Two things can be done with a selection. <b>Remove</b> cuts it out. <b>Replace in these
 * layers</b> keeps it and hands it to the ordinary Replace path as a {@link Scope}, so a material
 * row's Replace or a whole palette change then lands inside those layers only - the same screens,
 * the same confirm, a smaller set of positions.
 *
 * <p>No confirm screen for the cut, unlike a Replace. The counts are on the button before it is
 * pressed
 * rather than after, and the cut goes through {@code UndoLog} like every other change, so a
 * second screen would only be asking the same question twice.
 *
 * <p>Preview has two modes and neither one needed a render hook, because Litematica already
 * rebuilds its rendered world from the schematic on demand. <b>Highlight</b> points the live
 * render range at the selection, so the doomed layers are the only thing drawn. <b>Hidden</b>
 * performs the cut for real and takes it straight back off the undo stack when the selection
 * moves or the screen closes, so what you are looking at is the actual result rather than an
 * impression of it.
 */
public class LayersScreen extends GuiRenderLayerEditBase {

    /** Our range drives no rendering, so nothing needs invalidating when it moves. */
    private static final IRangeChangeListener NO_RENDER_UPDATE = new IRangeChangeListener() {

        @Override
        public void updateAll() {
        }

        @Override
        public void updateBetweenX(int min, int max) {
        }

        @Override
        public void updateBetweenY(int min, int max) {
        }

        @Override
        public void updateBetweenZ(int min, int max) {
        }
    };

    private final LitematicaSchematic schematic;
    private final SchematicPlacement placement;
    private final MaterialListBase materialList;
    private final LayerRange range;

    /**
     * Highlight isolates the selection in the live render range; hidden applies the cut and rolls
     * it back. Highlight leads because seeing the selection is the point of the screen, and it is
     * the cheap one - it moves the render range and touches no blocks.
     */
    private enum Preview {

        HIGHLIGHT("Preview: highlight", "Draw only the layers that would be removed"),
        HIDDEN("Preview: hidden", "Draw the schematic as it would be after the cut"),
        OFF("Preview: off", "The schematic renders as it is");
        final String label;
        final String hover;

        Preview(String label, String hover) {
            this.label = label;
            this.hover = hover;
        }

        Preview next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private Layers.Plan plan;
    private String plannedFor = "";

    /**
     * The last selection actually cut, and the preview mode it was cut with, kept for the rest of
     * the session. Removing one layer is usually followed by removing the next one, and re-typing
     * the axis and the number every time is the whole cost of the tool.
     */
    private static LayerRange lastUsed;
    private static Preview lastPreview = Preview.HIGHLIGHT;

    private Preview preview;

    private Direction.Axis lastAxis;
    private LayerMode lastMode;

    /** What is currently being previewed, so an unchanged selection is not re-cut every frame. */
    private String previewedFor = "";

    private ButtonGeneric removeButton;

    private ButtonGeneric replaceButton;

    /** Set when a value had to be pulled onto the schematic, so the screen can own up to it. */
    private String adjustment;

    /**
     * How long a value is left alone before it is held inside the schematic.
     *
     * <p>Typing "206" passes through 2 and 20 on the way, and clamping those would rewrite the
     * field under the cursor and make the number untypeable. Waiting for the value to stop moving
     * sidesteps that without having to know whether the user is typing - the first attempt used
     * the text field's focus for that, and malilib keeps the field focused, so the clamp never ran
     * at all and Set Here kept its off-build coordinate.
     */
    private static final long SETTLE_MS = 500;

    private long outOfSpanSince;
    private String outOfSpanValue;

    private boolean cutApplied;
    private boolean dirtyBeforePreview;
    private LayerRange savedRenderRange;

    public LayersScreen(LitematicaSchematic schematic,
                        SchematicPlacement placement,
                        MaterialListBase materialList) {
        this.schematic = schematic;
        this.placement = placement;
        this.materialList = materialList;
        this.range = new LayerRange(NO_RENDER_UPDATE);

        // Deliberately never seeded from Litematica's render layers. The two are separate tools:
        // this one selects what to delete, that one selects what to look at, and borrowing its
        // numbers is what made a render range left on "single layer X" turn up as the starting
        // selection for a cut. The render range is touched only to show a preview, and put back.
        if (lastUsed != null) {
            copyInto(lastUsed, this.range);
        } else {
            this.range.setLayerMode(LayerMode.SINGLE_LAYER);
            this.range.setAxis(Direction.Axis.Y);
        }

        this.preview = lastPreview;
        this.lastAxis = this.range.getAxis();
        this.lastMode = this.range.getLayerMode();
        this.useTitleHierarchy = false;
        this.setTitle("Layers");

        Layers.auditAgainstPlacement(schematic, placement);

        if (lastUsed == null) {
            recentreOnAxis();          // a fresh range is all zeroes; put it on the build
        } else {
            snapIntoSchematic();       // the remembered one may not fit this schematic
        }
    }

    /**
     * Every field the render layers menu can set, copied across.
     *
     * <p>Max before min: the two range setters clamp against each other, so setting a min above
     * the target's current max would drag the max along with it.
     */
    private static void copyInto(LayerRange from, LayerRange to) {
        to.setAxis(from.getAxis());
        to.setLayerSingle(from.getLayerSingle());
        to.setLayerAbove(from.getLayerAbove());
        to.setLayerBelow(from.getLayerBelow());
        setBounds(to, from.getLayerRangeMin(), from.getLayerRangeMax());
        // Mode last, so it applies to values that have already settled.
        to.setLayerMode(from.getLayerMode());
    }

    /**
     * Sets both ends of a layer range exactly, whatever the range held before.
     *
     * <p>The two setters clamp against each other - {@code setLayerRangeMin} caps at the current
     * max, {@code setLayerRangeMax} floors at the current min - so writing a Z window of 206 to
     * 256 into a range still holding an X window can leave one end pinned to the old value, and a
     * window whose ends have crossed selects nothing at all. That is the layer-range-on-Z case
     * where the highlight preview rendered nothing.
     *
     * <p>Min, then max, then min again is exact for any starting state: the middle call is floored
     * by a min that is already at or below the target, and the last is capped by a max that is now
     * exactly the target.
     */
    private static void setBounds(LayerRange target, int min, int max) {
        target.setLayerRangeMin(min);
        target.setLayerRangeMax(max);
        target.setLayerRangeMin(min);
    }

    @Override
    protected LayerRange getLayerRange() {
        return range;
    }

    @Override
    public void initGui() {
        super.initGui();

        // Before the controls are built, so the text fields show the values we land on.
        //
        // Mode counts as well as axis: the mode is what decides *which* value gets read, so
        // leaving "all" - which reads none of them - for single layer surfaces whatever was in
        // that field last without the axis ever changing. That is how a layer of -207 survived
        // against a build spanning -183 to -133.
        if (lastAxis != range.getAxis() || lastMode != range.getLayerMode()) {
            snapIntoSchematic();
            lastAxis = range.getAxis();
            lastMode = range.getLayerMode();
        }

        this.createLayerEditControls(10, 32, this.range);

        refreshPlan();

        int y = buttonRowY();

        // Scroll is deliberately dead on this one. malilib routes the wheel to whatever button is
        // under the cursor, and on a button that deletes blocks a stray scroll is a destructive
        // click nobody made.
        //
        // Fixed width, because the label is rewritten in place as the selection moves and a
        // button that resizes under the cursor is its own small bug.
        removeButton = new ButtonGeneric(10, y, 170, 20, "") {

            @Override
            public boolean onMouseScrolledImpl(double mouseX, double mouseY,
                                               double horizontal, double vertical) {
                return false;
            }
        };

        updateRemoveButton();
        this.addButton(removeButton, this::onRemove);

        int previewX = 10 + removeButton.getWidth() + 4;
        int previewWidth = 0;

        for (Preview mode : Preview.values()) {
            previewWidth = Math.max(previewWidth, this.getStringWidth(mode.label) + 20);
        }

        ButtonGeneric previewButton =
                new ButtonGeneric(previewX, y, previewWidth, 20, preview.label);
        previewButton.setEnabled(!selectsEverything());
        previewButton.setHoverStrings(selectsEverything()
                ? "Nothing to preview: this mode selects the whole schematic"
                : preview.hover);

        this.addButton(previewButton, (button, mouseButton) -> {
            preview = preview.next();
            lastPreview = preview;
            // initGui re-runs, which re-reads the mode and calls syncPreview below.
            this.initGui();
        });

        this.addButton(new ButtonGeneric(previewX + previewWidth + 4, y, 80, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));

        // Second row, away from Remove: the two buttons act on the same selection but one deletes
        // it and the other keeps it, and sharing a row is how a mis-click gets made. Fixed width
        // for the same reason Remove has one - the count lives in the hover, not the label.
        replaceButton = new ButtonGeneric(10, y + 24, 170, 20, "Replace in these layers") {

            @Override
            public boolean onMouseScrolledImpl(double mouseX, double mouseY,
                                               double horizontal, double vertical) {
                return false;
            }
        };

        updateReplaceButton();
        this.addButton(replaceButton, this::onReplaceInLayers);

        syncPreview();
    }

    /**
     * Hands the selection to the ordinary Replace path instead of cutting it.
     *
     * <p>No confirm and no work done here: this only sets {@link Scope} and returns to the
     * material list, where a row's Replace and Change palette then confine themselves to these
     * layers and show their own counts and confirm as they always do.
     */
    private void onReplaceInLayers(ButtonBase button, int mouseButton) {
        // The hidden preview is a real cut on the undo stack. Leaving it applied while the user
        // goes off to replace something would mean replacing inside a schematic that is missing
        // the very layers they just chose.
        clearPreview();

        if (selectsEverything()) {
            // "All" is the whole schematic, which is what no scope already means. Setting one
            // would only leave a confining-looking button on a list that is not confined.
            Scope.clear();
        } else {
            Scope.set(schematic, Layers.mask(schematic, placement, range),
                    range.getAxis() + " " + range.getCurrentLayerString());

            lastUsed = new LayerRange(NO_RENDER_UPDATE);
            copyInto(range, lastUsed);
            lastPreview = preview;
        }

        this.closeGui(true);
    }

    private void updateReplaceButton() {
        if (replaceButton == null || plan == null) {
            return;
        }

        // Blocks only, unlike Remove: a layer holding one armour stand and no blocks is worth
        // cutting, but there is nothing in it for a material swap to convert.
        boolean nothing = plan.removed() == 0 && !selectsEverything();

        replaceButton.setEnabled(!nothing);
        replaceButton.setHoverStrings(nothing
                ? "No blocks inside these layers"
                : selectsEverything()
                        ? "The whole schematic - clears any layer selection from Replace"
                        : plan.removed() + " blocks inside: Replace and Change palette will only"
                                + " touch those until you clear it");
    }

    private void updateRemoveButton() {
        if (removeButton == null || plan == null) {
            return;
        }

        boolean nothing = plan.removesNothing();

        // A layer with an armour stand in it and no blocks is still worth cutting, so the
        // button says what is actually there rather than "Remove 0 blocks".
        String what = plan.removed() == 0 && plan.entities() > 0
                ? plan.entities() + (plan.entities() == 1 ? " entity" : " entities")
                : plan.removed() + (plan.removed() == 1 ? " block" : " blocks");

        removeButton.setDisplayString(nothing ? "Nothing selected" : "Remove " + what);
        removeButton.setEnabled(!nothing);

        // No arguments, not an empty string: one empty string is still one hover line, which
        // malilib duly renders as an empty tooltip box.
        if (nothing) {
            removeButton.setHoverStrings("Nothing falls inside these layers");
        } else {
            removeButton.setHoverStrings();
        }
    }

    /**
     * Brings the counts, the button and the preview into line with the selection, wherever the
     * selection was changed from. Guarded on the key, so it costs one string compare when nothing
     * has moved.
     *
     * <p>Driven from {@link #drawContents} rather than only from {@code tick()}. Malilib's own
     * mode, axis and adjust controls do not re-run our {@code initGui}, and the tick hook turned
     * out not to reach this screen at all - which is why only "Set Here" ever appeared to work,
     * and why the axis button's habit of re-anchoring the layer to the player's own position went
     * uncorrected. Nothing here touches the widget list, so it is safe mid-frame: values, one
     * button's label, and the preview.
     */
    private void syncSelection() {
        holdInsideSchematic();

        if (selectionKey().equals(plannedFor)) {
            return;
        }

        if (lastAxis != range.getAxis() || lastMode != range.getLayerMode()) {
            snapIntoSchematic();
            lastAxis = range.getAxis();
            lastMode = range.getLayerMode();
        }

        refreshPlan();
        updateRemoveButton();
        updateReplaceButton();
        syncPreview();
    }

    /**
     * A wheel over the adjust arrows, or a click on them, holds the value at the edge at once.
     *
     * <p>Stepping is the one source where the intent is unambiguous - nobody steps past the end of
     * the build on purpose - so there is nothing to wait for, and waiting is visible: the layer
     * renders outside the schematic until the delay is up. Typing still settles first, because a
     * half-typed number genuinely is out of bounds on its way to being in bounds.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        boolean handled = super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        clampNow();

        return handled;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        // Covers the + and - buttons and Set Here alike. Set Here is allowed to aim outside the
        // build - that is where the player is standing - it just does not get to stay there.
        clampNow();

        return handled;
    }

    /** Clamp immediately and restart the settle watchdog, so it cannot fire again on its own. */
    private void clampNow() {
        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span != null && range.getLayerMode() != LayerMode.ALL && !anchorWithin(span)) {
            clampIntoSchematic();
        }

        outOfSpanSince = 0;
        outOfSpanValue = null;
    }

    /**
     * Pulls the selection onto the schematic once it has sat outside it, unmoving, for
     * {@link #SETTLE_MS}.
     *
     * <p>Watches the value itself rather than hanging off the change-detection key. The previous
     * attempt armed a deadline whenever the selection moved, which any further movement pushed
     * back, so a value that arrived out of bounds and stayed there - exactly what Set Here does
     * from outside the build - never reached its own deadline. This cannot be starved: being out
     * of bounds is the trigger, and only the value changing restarts the clock.
     */
    private void holdInsideSchematic() {
        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span == null || range.getLayerMode() == LayerMode.ALL || anchorWithin(span)) {
            outOfSpanSince = 0;
            outOfSpanValue = null;
            return;
        }

        String value = anchorText();
        long now = System.currentTimeMillis();

        if (!value.equals(outOfSpanValue)) {
            // Still being typed or stepped; let it move.
            outOfSpanValue = value;
            outOfSpanSince = now;
            return;
        }

        if (now - outOfSpanSince >= SETTLE_MS) {
            clampIntoSchematic();
            outOfSpanSince = 0;
            outOfSpanValue = null;
        }
    }

    /**
     * Moves the selection onto the schematic when it is pointing somewhere else entirely.
     *
     * <p>Only fires on open and when the axis or the mode changes, never mid-edit: those are the
     * controls that change which value is read, so a number you typed yourself is never fought.
     *
     * <p>This is the axis bug the axis-change check alone did not cover. Open the tool with the
     * axis already on Z and a layer value of 186 while the build spans Z 206 to 256, and the axis
     * never changes, so nothing re-seeds: single layer and range select nothing, and all-above
     * selects the entire build because 186 is below all of it. Both readings look like a broken
     * tool rather than a number pointing off the end of the world.
     */
    private void snapIntoSchematic() {
        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span != null && !anchorWithin(span)) {
            recentreOnAxis();

            // The text field caches its own string, so without this it keeps showing the value we
            // just moved away from - which reads as the snap not having happened.
            if (this.textField1 != null) {
                this.updateTextFieldValues(range);
            }
        }
    }

    /**
     * Holds the selection inside the schematic.
     *
     * <p>The layer controls are Litematica's, and they are built for a render range that may
     * legitimately sit anywhere in the world - the adjust arrows walk off the build happily, and
     * the axis button anchors to wherever the player is standing, which is how a layer of -209
     * kept turning up against a build spanning -183 to -133. Nothing outside the schematic can
     * remove anything, so the bounds are the schematic's.
     *
     * <p>Clamped to the near edge rather than re-centred, so walking the arrows towards the end
     * of the build stops at the end instead of jumping to the middle.
     *
     * <p>Only once the value has stopped moving - see {@link #SETTLE_MS}. Clamping a half-typed
     * number is how a field you cannot type "112" into gets made, because the "1" is corrected
     * before the rest arrives.
     */
    private void clampIntoSchematic() {
        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span == null) {
            return;
        }

        String was = anchorText();

        switch (range.getLayerMode()) {
            case ALL -> {
                return;                                    // reads no value, so none to hold
            }
            case SINGLE_LAYER -> range.setLayerSingle(clamp(span, range.getLayerSingle()));
            case ALL_ABOVE -> range.setLayerAbove(clamp(span, range.getLayerAbove()));
            case ALL_BELOW -> range.setLayerBelow(clamp(span, range.getLayerBelow()));
            case LAYER_RANGE -> setBounds(range,
                    clamp(span, range.getLayerRangeMin()), clamp(span, range.getLayerRangeMax()));
        }

        String now = anchorText();

        // Set Here anchors to where the player is standing, which is regularly off the build. It
        // used to be pulled onto the nearest layer in silence, so the number that appeared was
        // not the one the button implied and looked like the wrong coordinate.
        if (was.equals(now)) {
            adjustment = null;
        } else {
            adjustment = "moved " + was + " to " + now + " - nearest layer on the schematic";
            LetMeReplace.LOGGER.info("held selection inside the schematic: {} -> {} on {}",
                    was, now, range.getAxis());
        }

        if (this.textField1 != null) {
            this.updateTextFieldValues(range);
        }
    }

    /**
     * Which way the numbers run on this axis.
     *
     * <p>Only Y is vertical. X and Z are horizontal - X rising to the east, Z rising to the south
     * - so "all above" on Z means everything to the south of the layer, not everything over it.
     * The mode button says "above" and "below" whatever the axis, so the screen has to be the
     * thing that says what those mean here.
     */
    private String lowEnd() {
        return switch (range.getAxis()) {
            case X -> "west";
            case Y -> "bottom";
            case Z -> "north";
        };
    }

    private String highEnd() {
        return switch (range.getAxis()) {
            case X -> "east";
            case Y -> "top";
            case Z -> "south";
        };
    }

    /** The value(s) the current mode reads, for reporting a correction. */
    private String anchorText() {
        return switch (range.getLayerMode()) {
            case ALL -> "";
            case SINGLE_LAYER -> String.valueOf(range.getLayerSingle());
            case ALL_ABOVE -> String.valueOf(range.getLayerAbove());
            case ALL_BELOW -> String.valueOf(range.getLayerBelow());
            case LAYER_RANGE -> range.getLayerRangeMin() + ".." + range.getLayerRangeMax();
        };
    }


    private static int clamp(int[] span, int value) {
        return Math.max(span[0], Math.min(span[1], value));
    }

    /** Whether the value this mode actually reads sits on the schematic at all. */
    private boolean anchorWithin(int[] span) {
        return switch (range.getLayerMode()) {
            case ALL -> true;
            case SINGLE_LAYER -> within(span, range.getLayerSingle());
            case ALL_ABOVE -> within(span, range.getLayerAbove());
            case ALL_BELOW -> within(span, range.getLayerBelow());
            case LAYER_RANGE ->
                    within(span, range.getLayerRangeMin()) || within(span, range.getLayerRangeMax());
        };
    }

    private static boolean within(int[] span, int value) {
        return value >= span[0] && value <= span[1];
    }

    /**
     * Puts every layer value in the middle of the schematic on the newly chosen axis.
     *
     * <p>The numbers are world coordinates, so switching from Y to Z leaves a Y value like 64
     * pointing at a Z the build does not occupy: every mode selects nothing, and the screen looks
     * broken rather than empty. The middle is chosen because each mode then selects something
     * obviously adjustable - one layer for single and range, half the build for above and below -
     * rather than everything, which for a delete tool is the wrong thing to land on by accident.
     */
    private void recentreOnAxis() {
        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span == null) {
            return;
        }

        int middle = span[0] + (span[1] - span[0]) / 2;

        range.setLayerSingle(middle);
        range.setLayerAbove(middle);
        range.setLayerBelow(middle);
        setBounds(range, middle, middle);
    }

    /**
     * {@code ALL} selects the entire schematic, which makes both preview modes pointless and one
     * of them alarming: highlight would highlight everything, and hidden would delete the whole
     * build and rebuild it on every adjustment. The cut itself is still allowed - the button says
     * it removes every block - but nothing previews it.
     */
    private boolean selectsEverything() {
        return range.getLayerMode() == LayerMode.ALL;
    }

    /**
     * Every number the selection depends on, not {@code getCurrentLayerString()}. The display
     * string only renders the values the current mode uses, and for some modes it does not move
     * when the value behind it does - so a key built from it silently stopped noticing edits.
     */
    private static String describe(Rotation rotation) {
        return switch (rotation) {
            case NONE -> "unrotated";
            case CLOCKWISE_90 -> "rotated 90";
            case CLOCKWISE_180 -> "rotated 180";
            case COUNTERCLOCKWISE_90 -> "rotated 270";
        };
    }

    private static String describeMirror(Mirror mirror) {
        return mirror == Mirror.NONE ? "" : ", mirrored " + mirror.getSerializedName();
    }

    /** Top of the readout under the layer controls. */
    /** " - takes everything south of 231", so "above" is never left to mean up on a flat axis. */
    private String selectionInWords() {
        return switch (range.getLayerMode()) {
            case ALL -> "  - the whole schematic";
            case SINGLE_LAYER -> "";
            case ALL_ABOVE -> "  - takes everything " + highEnd() + " of " + range.getLayerAbove();
            case ALL_BELOW -> "  - takes everything " + lowEnd() + " of " + range.getLayerBelow();
            case LAYER_RANGE -> "";
        };
    }

    private int textTop() {
        return Math.max(this.nextY + 6, 78);
    }

    /**
     * Always four lines of room, whether or not all four are in use. The warning line used to be
     * drawn straight through the button row, and a button row that moves depending on whether a
     * warning is showing is the other half of the same bug.
     */
    private int buttonRowY() {
        return textTop() + 7 * 12 + 8;          // up to seven lines of counts and warnings
    }

    private String selectionKey() {
        return range.getLayerMode() + "/" + range.getAxis()
                + "/" + range.getLayerSingle()
                + "/" + range.getLayerAbove()
                + "/" + range.getLayerBelow()
                + "/" + range.getLayerRangeMin()
                + "/" + range.getLayerRangeMax();
    }

    /**
     * Follows the selection however it moved.
     *
     * <p>Only the buttons re-run {@code initGui}, so typing a value into a field or holding an
     * adjust arrow changed the range without the preview or the block count hearing about it -
     * "Set here" appeared to be the only control that worked, because it happens to be a button.
     * Watching the selection from the tick catches all of them, and costs one string compare 20
     * times a second when nothing is moving.
     */
    @Override
    public void tick() {
        super.tick();
        syncSelection();
    }

    /**
     * Brings what is on screen into line with the chosen mode and the current selection, and does
     * nothing when both are unchanged - otherwise moving a layer by one would re-cut and rebuild
     * the whole schematic on every key press.
     */
    private void syncPreview() {
        String wanted = preview == Preview.OFF || selectsEverything()
                ? ""
                : preview + "|" + selectionKey();

        if (wanted.equals(previewedFor)) {
            return;
        }

        clearPreview();

        if (preview == Preview.HIGHLIGHT) {
            borrowRenderRange();
            copyInto(range, DataManager.getRenderLayerRange());
        } else if (preview == Preview.HIDDEN) {
            // Show the whole schematic while the cut is applied, or the preview is invisible.
            // Litematica's render range is whatever the user last left it on - single layer on X
            // in the session this was found in - and handing that back mid-preview both hides the
            // result and reads as the tool having changed the axis on its own.
            borrowRenderRange();
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

            dirtyBeforePreview = schematic.getMetadata().wasModifiedSinceSaved();
            cutApplied = Layers.apply(schematic, placement, range, "Preview") > 0;
        }

        previewedFor = wanted;
        quiet();
    }

    /**
     * Litematica announces every render-range write on the action bar ("Set layer mode to ..."),
     * and the preview writes it on every change and puts it back on close - the line showed
     * through between this screen's buttons and outlived it onto the next one. Ours is a borrow,
     * not the user changing their layers, so it is not news.
     */
    private static void quiet() {
        net.minecraft.client.Minecraft.getInstance().gui.hud
                .setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
    }

    /** Keeps the user's own render range to put back, once, before we start writing to it. */
    private void borrowRenderRange() {
        if (savedRenderRange == null) {
            savedRenderRange = new LayerRange(NO_RENDER_UPDATE);
            copyInto(DataManager.getRenderLayerRange(), savedRenderRange);
        }
    }


    /**
     * Puts back whatever the preview borrowed. Safe to call when there is nothing to undo, and
     * called from every exit as well as before each re-preview.
     *
     * <p>The dirty flag needs restoring by hand: {@code Layers.apply} sets it and
     * {@code UndoLog.undo} deliberately does not clear it, so without this a preview that was
     * rolled back would leave Save lit with nothing to write.
     */
    private void clearPreview() {
        if (cutApplied) {
            UndoLog.takeBack(schematic);

            if (!dirtyBeforePreview) {
                schematic.getMetadata().clearModifiedSinceSaved();
            }

            cutApplied = false;
        }

        if (savedRenderRange != null) {
            copyInto(savedRenderRange, DataManager.getRenderLayerRange());
            savedRenderRange = null;
            quiet();
        }

        previewedFor = "";
    }

    @Override
    public void removed() {
        // The backstop. Escape, the parent reopening and anything else that takes the screen away
        // without going through a button all land here, and a preview left applied would be an
        // edit the user never asked for.
        clearPreview();
        super.removed();
    }

    /**
     * The counts come from a dry run over the whole schematic. That walk is repeated whenever the
     * selection changes - malilib re-runs {@code initGui} on every button press - so it is skipped
     * when the range has not actually moved, which is most of the calls.
     */
    private void refreshPlan() {
        String key = selectionKey();

        if (plan != null && key.equals(plannedFor)) {
            return;
        }

        // The count has to be taken against the schematic as it really is. A hidden preview has
        // already removed those blocks, so planning on top of one would report nothing left to
        // remove and grey the button out - the selection moved, so the preview is stale anyway.
        clearPreview();

        plan = Layers.plan(schematic, placement, range);
        plannedFor = key;
    }

    private void onRemove(ButtonBase button, int mouseButton) {
        // Roll the preview back and cut again properly rather than adopting it: the preview's
        // snapshot is labelled "Preview" and its dirty handling is the borrowed kind, and one
        // extra walk is a cheap price for the committed change going through the ordinary path.
        clearPreview();

        String label = "Remove layers: " + range.getAxis() + " " + range.getCurrentLayerString();
        int removed = Layers.apply(schematic, placement, range, label);

        if (removed > 0) {
            // "Latest use" means the one that cut something, not every fiddle with the controls.
            lastUsed = new LayerRange(NO_RENDER_UPDATE);
            copyInto(range, lastUsed);
            lastPreview = preview;

            // One queued change per gesture, the same way a palette change of 27 block pairs
            // counts once - see ConfirmScreen.
            Pending.recordSwap(schematic);
            dev.matt.letmereplace.Recount.later(materialList);
        }

        this.closeGui(true);
    }

    @Override
    protected void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        syncSelection();

        int x = 10;
        int y = textTop();

        if (placement == null) {
            // Nothing to map through, so the numbers are the schematic's own rather than the
            // world's. Saying so beats letting them silently disagree with the render menu.
            this.drawString(context, "No placement: layers are counted from the schematic itself",
                    x, y, Ui.GOLD);
        } else if (placement.getRotation() != Rotation.NONE
                || placement.getMirror() != Mirror.NONE) {
            // A rotated placement is the case where world layers and the schematic's own axes
            // stop agreeing, so say that it is being accounted for rather than leaving the user
            // to wonder whether X means X.
            this.drawString(context, "placement " + describe(placement.getRotation())
                    + describeMirror(placement.getMirror()) + " - layers follow the world", x, y,
                    Ui.GREY);
        }

        y += 12;

        if (plan == null) {
            return;
        }

        this.drawString(context, "removes " + plan.removed() + ", keeps " + plan.kept()
                + selectionInWords(), x, y, Ui.GREY);

        // Its own line: beside the counts it ran into them once the selection words grew.
        if (plan.blockEntities() > 0) {
            y += 12;
            this.drawString(context,
                    plan.blockEntities() + " block entities go with them (contents included)",
                    x, y, Ui.RED);
        }

        if (plan.entities() > 0) {
            y += 12;
            this.drawString(context,
                    plan.entities() + " entities stand in them and go too", x, y, Ui.GOLD);
        }

        y += 12;

        int[] span = Layers.worldSpan(schematic, placement, range.getAxis());

        if (span != null) {
            // Without this, "nothing selected" on X or Z is indistinguishable from a broken tool.
            String where = "schematic spans " + range.getAxis().getSerializedName().toUpperCase()
                    + " " + span[0] + " to " + span[1] + "  (" + lowEnd() + " to " + highEnd() + ")";
            this.drawString(context, where, x, y, plan.removesNothing() ? Ui.GOLD : Ui.GREY);
        }

        y += 12;

        if (plan.removesEverything()) {
            this.drawString(context, "This removes every block in the schematic.", x, y, Ui.RED);
        }

        y += 12;

        if (adjustment != null) {
            this.drawString(context, adjustment, x, y, Ui.GOLD);
        }
    }
}
