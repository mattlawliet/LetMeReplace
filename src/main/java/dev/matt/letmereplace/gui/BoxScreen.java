package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.BoxEdit;
import dev.matt.letmereplace.Layers;
import dev.matt.letmereplace.Pending;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Stack or move a box of the schematic inside itself - ticket 007, the screen half.
 *
 * <p>The box <strong>is</strong> Litematica's selected area-selection box. The six fields are a
 * view of it, not a copy: typing in them moves Litematica's outline, and there is nothing of ours
 * to keep in step. Setting the corners with Litematica's tool and opening this screen is the
 * other way in.
 *
 * <p>The result is shown live, the way the layers screen's "Preview: hidden" is: whichever row
 * was touched last - Stack or Move - is applied for real and taken off again before the next
 * change, so the build in the world is the build you would get. It is kept off the undo stack
 * (see {@link BoxEdit#preview}), and the Stack and Move buttons take it off and apply the change
 * properly, as one undo entry.
 *
 * <p>ponytail: the seam check and the in-world scroll modes are the ticket's later steps.
 */
public class BoxScreen extends GuiBase {

    /** Session memory, like the slab button: the same tile is usually stacked more than once. */
    /** Package-visible: the in-world modes follow the same choice. */
    static boolean airReplaces = true;
    private static Direction toward;
    private static int count = 1;
    private static boolean previewOn = true;

    /** Which row the live preview shows: the one touched last. */
    private enum Live { STACK, MOVE }

    private Live live = Live.STACK;

    /** Set while the would-not-survive list is up, instead of the controls. */
    private Live reviewing;
    private BoxEdit.Plan reviewPlan;
    private final LivePreview preview = new LivePreview();
    private String syncedFor = "";

    private final LitematicaSchematic schematic;
    private final SchematicPlacement placement;
    private final MaterialListBase materialList;

    private int step;
    private final int[] moveBy = new int[3];

    private BoxEdit.Plan stackPlan;
    private BoxEdit.Plan movePlan;

    private final List<IntField> fields = new ArrayList<>();

    private ButtonGeneric stackButton;
    private ButtonGeneric moveButton;

    private int boxY;
    private int stackY;
    private int moveY;
    private String problem;

    public BoxScreen(LitematicaSchematic schematic, SchematicPlacement placement,
                     MaterialListBase materialList) {
        this.schematic = schematic;
        this.placement = placement;
        this.materialList = materialList;
        this.useTitleHierarchy = false;
        this.setTitle("Box");

        if (toward == null) {
            var player = Minecraft.getInstance().player;
            toward = player != null ? player.getDirection() : Direction.EAST;
        }

        Box box = box();

        if (box != null) {
            step = BoxEdit.length(box.getPos1(), box.getPos2(), toward);
        }
    }

    private static AreaSelection selection() {
        return DataManager.getSelectionManager().getCurrentSelection();
    }

    /** The selected box, or {@code null} until both its corners are set. */
    private static Box box() {
        AreaSelection selection = selection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();

        return box == null || box.getPos1() == null || box.getPos2() == null ? null : box;
    }

    @Override
    public void initGui() {
        super.initGui();

        if (reviewing != null) {
            initReview();
            return;
        }

        Box box = box();
        problem = placement == null
                ? "Stacking needs a placed schematic: open the material list from its placement."
                : box == null
                        ? "No box selected. Set two corners with Litematica's area selection tool."
                        : null;

        int x = 10;
        int y = 32;

        fields.clear();

        if (problem != null) {
            y = 52;

            if (placement != null) {
                ButtonGeneric around = new ButtonGeneric(x, y, -1, 20, "Box around the placement");
                around.setHoverStrings("Select the whole schematic as the box");
                this.addButton(around, this::onBoxAroundPlacement);
                y += 24;
            }
        }

        if (problem == null) {
            boxY = y;
            cornerFields(x + 40, y, Corner.CORNER_1, box.getPos1());

            y += 22;
            cornerFields(x + 40, y, Corner.CORNER_2, box.getPos2());

            y += 24;
            int bx = x + 40;

            for (Direction.Axis axis : Direction.Axis.values()) {
                String name = axis.getSerializedName().toUpperCase(java.util.Locale.ROOT);
                ButtonGeneric whole = new ButtonGeneric(bx, y - 2, -1, 20, "Whole " + name);
                whole.setHoverStrings("Stretch the box across the whole schematic on " + name);
                this.addButton(whole, (button, mouseButton) -> onWhole(axis));
                bx += whole.getWidth() + 4;
            }

            ButtonGeneric fit = new ButtonGeneric(bx, y - 2, -1, 20, "Fit to contents");
            fit.setHoverStrings("Shrink the box to the blocks and entities inside it");
            this.addButton(fit, this::onFit);
            bx += fit.getWidth() + 4;

            this.addButton(new ButtonGeneric(bx, y - 2, -1, 20, airLabel()),
                    (button, mouseButton) -> {
                        airReplaces = !airReplaces;
                        button.setDisplayString(airLabel());
                    });

            ButtonGeneric previewButton = new ButtonGeneric(this.width - 10 - 90, 32 - 2, 90, 20,
                    previewLabel());
            previewButton.setHoverStrings("Show the stack or move in the world as you change it",
                    "Turn off if a big box makes every change slow");
            this.addButton(previewButton, (button, mouseButton) -> {
                previewOn = !previewOn;
                button.setDisplayString(previewLabel());
            });

            y += 30;
            stackY = y;
            int fx = x + 40;
            fx = intField(fx, y, 32, count, 1, value -> {
                count = value;
                live = Live.STACK;
            });

            ButtonGeneric towardButton = new ButtonGeneric(fx + 4, y - 2, 80, 20, towardLabel());
            this.addButton(towardButton, (button, mouseButton) -> {
                Direction[] all = Direction.values();
                toward = all[(toward.ordinal() + (mouseButton == 1 ? all.length - 1 : 1)) % all.length];
                Box current = box();
                step = current == null ? step : BoxEdit.length(current.getPos1(), current.getPos2(), toward);
                live = Live.STACK;
                this.initGui();          // the step field shows the new default
            });
            fx += 92 + this.getStringWidth("step") + 4;
            fx = intField(fx, y, 32, step, 1, value -> {
                step = value;
                live = Live.STACK;
            });

            stackButton = noScroll(fx + 12, y - 2, 110, "Stack");
            this.addButton(stackButton, this::onStack);

            y += 38;                   // room for the summary line under the row
            moveY = y;
            fx = x + 40 + this.getStringWidth("by") + 4;

            for (int i = 0; i < 3; ++i) {
                int axis = i;
                fx = intField(fx, y, 32, moveBy[i], Integer.MIN_VALUE, value -> {
                    moveBy[axis] = value;
                    live = Live.MOVE;
                }) + 4;
            }

            moveButton = noScroll(stackButton.getX(), y - 2, 110, "Move");
            this.addButton(moveButton, this::onMove);

            y += 42;
        }

        // Cancel last, as on every other LetMeReplace screen: actions first, the way out after.
        int cancelX = x;

        if (problem == null) {
            ButtonGeneric stackInWorld = new ButtonGeneric(x, y, -1, 20, "Stack in world...");
            stackInWorld.setHoverStrings("Scroll to add copies toward where you look",
                    "Litematica's Execute operation keeps it, Escape puts it back");
            this.addButton(stackInWorld, (button, mouseButton) -> toWorld(WorldMode.Kind.STACK));

            ButtonGeneric moveInWorld = new ButtonGeneric(x + 4 + stackInWorld.getWidth(), y, -1,
                    20, "Move in world...");
            moveInWorld.setHoverStrings("Scroll to move the box toward where you look",
                    "Sneak to move a whole box length per notch");
            this.addButton(moveInWorld, (button, mouseButton) -> toWorld(WorldMode.Kind.MOVE));
            cancelX = moveInWorld.getX() + moveInWorld.getWidth() + 4;
        }

        this.addButton(new ButtonGeneric(cancelX, y, 80, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));

        ButtonGeneric settings = new ButtonGeneric(this.width - 10 - 70, this.height - 30, 70, 20,
                "Settings...");
        settings.setHoverStrings("Hotkeys, undo and redo");
        this.addButton(settings, (button, mouseButton) ->
                Screens.open(new SettingsScreen(), this.getParent()));
    }

    /** Closes every screen - this one's preview comes off on the way - and arms the mode. */
    private void toWorld(WorldMode.Kind kind) {
        GuiBase.openGui(null);
        WorldMode.arm(kind, true);
    }

    /**
     * The seam review, in place of the controls. It is this screen rather than a new one so the
     * one-deep screen rule needs no exception, and so the preview stays on behind it - the
     * blocks being asked about are right there in the world.
     */
    private void initReview() {
        int y = this.height - 40;
        int x = 10;

        String what = reviewing == Live.STACK ? "stack" : "move";

        ButtonGeneric keep = noScroll(x, y, 100, "Keep them");
        keep.setHoverStrings("Do the " + what + " and leave these blocks as they are");
        this.addButton(keep, (button, mouseButton) -> finishReview(false));

        ButtonGeneric remove = noScroll(x + 104, y, 100, "Remove them");
        remove.setHoverStrings("Do the " + what + " and clear these blocks to air");
        this.addButton(remove, (button, mouseButton) -> finishReview(true));

        this.addButton(new ButtonGeneric(x + 208, y, 70, 20, "Back"), (button, mouseButton) -> {
            reviewing = null;
            this.initGui();
        });

        this.addButton(new ButtonGeneric(x + 282, y, 70, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));
    }

    private void finishReview(boolean dropDoomed) {
        Live which = reviewing;
        reviewing = null;

        if (which == Live.STACK) {
            doStack(dropDoomed);
        } else {
            doMove(dropDoomed);
        }
    }

    /** "2 x Wall Torch  at 104 156 -25, 104 156 -18", one line per kind of block. */
    static List<String> doomedLines(BoxEdit.Plan plan) {
        java.util.Map<String, List<BlockPos>> byBlock = new java.util.LinkedHashMap<>();

        for (BoxEdit.Doomed doomed : plan.wontSurvive()) {
            byBlock.computeIfAbsent(doomed.state().getBlock().getName().getString(),
                    k -> new ArrayList<>()).add(doomed.pos());
        }

        List<String> lines = new ArrayList<>();

        for (var entry : byBlock.entrySet()) {
            List<BlockPos> at = entry.getValue();
            StringBuilder line = new StringBuilder(at.size() + " x " + entry.getKey() + "  at ");

            for (int i = 0; i < Math.min(3, at.size()); ++i) {
                BlockPos pos = at.get(i);
                line.append(i > 0 ? ", " : "").append(pos.getX()).append(' ').append(pos.getY())
                        .append(' ').append(pos.getZ());
            }

            if (at.size() > 3) {
                line.append(", and ").append(at.size() - 3).append(" more");
            }

            lines.add(line.toString());
        }

        return lines;
    }

    /**
     * The whole placement as the box, creating a selection or a box in it when there is none -
     * the one-click way in when nothing has been selected yet.
     */
    private void onBoxAroundPlacement(ButtonBase button, int mouseButton) {
        clearPreview();                    // measure the schematic, not the preview of it
        int[][] span = new int[3][];

        for (Direction.Axis axis : Direction.Axis.values()) {
            span[axis.ordinal()] = Layers.worldSpan(schematic, placement, axis);

            if (span[axis.ordinal()] == null) {
                return;                                  // no regions, nothing to go around
            }
        }

        BlockPos min = new BlockPos(span[0][0], span[1][0], span[2][0]);
        BlockPos max = new BlockPos(span[0][1], span[1][1], span[2][1]);

        SelectionManager manager = DataManager.getSelectionManager();
        AreaSelection selection = manager.getCurrentSelection();

        if (selection == null) {
            String id = manager.createNewSelection(DataManager.getAreaSelectionsBaseDirectory(),
                    schematic.getMetadata().getName());
            manager.setCurrentSelection(id);
            selection = manager.getCurrentSelection();
        }

        if (selection != null && selection.getSelectedSubRegionBox() == null) {
            String name = selection.createNewSubRegionBox(min, selection.getName());

            if (name != null) {
                selection.setSelectedSubRegionBox(name);
            }
        }

        Box box = selection == null ? null : selection.getSelectedSubRegionBox();

        if (box == null) {
            this.addMessage(Message.MessageType.ERROR, "Could not create a selection box");
            return;
        }

        setCorners(selection, box, min, max);
    }

    private void onWhole(Direction.Axis axis) {
        Box box = box();
        AreaSelection selection = selection();

        if (box == null || selection == null) {
            return;
        }

        clearPreview();
        int[] span = Layers.worldSpan(schematic, placement, axis);

        if (span == null) {
            return;
        }

        BlockPos p1 = box.getPos1();
        BlockPos p2 = box.getPos2();
        int[] a = {p1.getX(), p1.getY(), p1.getZ()};
        int[] b = {p2.getX(), p2.getY(), p2.getZ()};
        int i = axis.ordinal();

        // Each corner keeps the side it was on, so the outline does not flip under the tool.
        boolean ascending = a[i] <= b[i];
        a[i] = ascending ? span[0] : span[1];
        b[i] = ascending ? span[1] : span[0];

        setCorners(selection, box, new BlockPos(a[0], a[1], a[2]), new BlockPos(b[0], b[1], b[2]));
    }

    private void onFit(ButtonBase button, int mouseButton) {
        Box box = box();
        AreaSelection selection = selection();

        if (box == null || selection == null) {
            return;
        }

        clearPreview();
        BlockPos[] fit = BoxEdit.fit(schematic, placement, box.getPos1(), box.getPos2());

        if (fit == null) {
            this.addMessage(Message.MessageType.WARNING, "Nothing in the box to fit to");
            return;
        }

        setCorners(selection, box, fit[0], fit[1]);
    }

    private void setCorners(AreaSelection selection, Box box, BlockPos one, BlockPos two) {
        selection.setSubRegionCornerPos(box, Corner.CORNER_1, one);
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, two);
        step = BoxEdit.length(one, two, toward);
        this.initGui();
    }

    /** Three fields for one corner, wired straight to Litematica's box. */
    private int cornerFields(int x, int y, Corner corner, BlockPos pos) {
        int[] value = {pos.getX(), pos.getY(), pos.getZ()};

        for (int i = 0; i < 3; ++i) {
            int axis = i;

            x = intField(x, y, 44, value[i], Integer.MIN_VALUE, typed -> {
                Box box = box();
                AreaSelection selection = selection();

                if (box == null || selection == null) {
                    return;
                }

                BlockPos at = corner == Corner.CORNER_1 ? box.getPos1() : box.getPos2();
                int[] xyz = {at.getX(), at.getY(), at.getZ()};
                xyz[axis] = typed;
                selection.setSubRegionCornerPos(box, corner, new BlockPos(xyz[0], xyz[1], xyz[2]));
            }) + 2;
        }

        ButtonGeneric here = new ButtonGeneric(x + 4, y - 2, -1, 20, "Set here");
        here.setHoverStrings("Put this corner where you are standing");
        this.addButton(here, (button, mouseButton) -> {
            var player = Minecraft.getInstance().player;
            Box box = box();
            AreaSelection selection = selection();

            if (player != null && box != null && selection != null) {
                selection.setSubRegionCornerPos(box, corner, player.blockPosition());
                this.initGui();                     // the fields show where it went
            }
        });

        return x + 4 + here.getWidth();
    }

    private int intField(int x, int y, int width, int initial, int min, IntConsumer onChange) {
        IntField field = new IntField(x, y, width, this.font, min, onChange);
        field.setValueWrapper(Integer.toString(initial));
        fields.add(field);

        this.addTextField(field, changed -> {
            try {
                field.onChange.accept(Math.max(min, Integer.parseInt(changed.getValue().trim())));
            } catch (NumberFormatException ignored) {
                // "-" on the way to "-30"
            }

            return true;
        });

        return x + width;
    }

    /**
     * The wheel over any number field steps it, ten at a time with shift - the same gesture as
     * the render layers menu's adjust arrows, without the arrows.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0) {
            for (IntField field : fields) {
                if (field.isMouseOver((int) mouseX, (int) mouseY)) {
                    field.nudge((vertical > 0 ? 1 : -1) * (isShiftDown() ? 10 : 1));
                    return true;
                }
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /** An integer field that selects all of itself when focused, so typing replaces it. */
    private static final class IntField extends GuiTextFieldInteger {

        private final int min;
        final IntConsumer onChange;

        IntField(int x, int y, int width, net.minecraft.client.gui.Font font, int min,
                 IntConsumer onChange) {
            super(x, y, width, 16, font);
            this.min = min;
            this.onChange = onChange;
        }

        @Override
        public void setFocused(boolean focused) {
            boolean gaining = focused && !this.isFocused();
            super.setFocused(focused);

            if (gaining) {
                selectAll();
            } else if (!focused) {
                // A selection left in a field you have moved off still draws as one.
                this.setHighlightPos(this.getCursorPosition());
            }
        }

        /**
         * Drags go to the focused field only. malilib offers a drag to every text field in the
         * order they were added and stops at the first that takes it, and an unfocused EditBox
         * takes it - so a drag in the count field selected text in the first corner's X.
         */
        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return this.isFocused() && super.mouseDragged(event, dragX, dragY);
        }

        /** The click that focuses also places the cursor, which drops the selection - redo it. */
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            boolean was = this.isFocused();
            boolean handled = super.mouseClicked(event, doubleClick);

            if (!was && this.isFocused()) {
                selectAll();
            }

            return handled;
        }

        private void selectAll() {
            this.moveCursorToEnd(false);
            this.setHighlightPos(0);
        }

        void nudge(int by) {
            int value;

            try {
                value = Integer.parseInt(this.getValue().trim());
            } catch (NumberFormatException e) {
                value = 0;
            }

            value = Math.max(min, value + by);
            this.setValueWrapper(Integer.toString(value));
            onChange.accept(value);
        }
    }

    /** Scroll is dead on the buttons that change the schematic - see LayersScreen. */
    private static ButtonGeneric noScroll(int x, int y, int width, String label) {
        return new ButtonGeneric(x, y, width, 20, label) {

            @Override
            public boolean onMouseScrolledImpl(double mouseX, double mouseY,
                                               double horizontal, double vertical) {
                return false;
            }
        };
    }

    private static String airLabel() {
        return airReplaces ? "Air: replaces" : "Air: skipped";
    }

    private static String previewLabel() {
        return previewOn ? "Preview: on" : "Preview: off";
    }

    private static String towardLabel() {
        String name = toward.getSerializedName();
        return "toward " + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private List<Vec3i> stackOffsets() {
        return BoxEdit.stackOffsets(toward, step, count);
    }

    private List<Vec3i> moveOffsets() {
        return List.of(new Vec3i(moveBy[0], moveBy[1], moveBy[2]));
    }

    private boolean moveIsZero() {
        return moveBy[0] == 0 && moveBy[1] == 0 && moveBy[2] == 0;
    }

    /** Everything the preview depends on. Unchanged means nothing to rebuild. */
    private String key() {
        Box box = box();

        return problem != null || box == null ? "" : live + "|" + previewOn + "|" + airReplaces
                + "|" + toward + "|" + step + "|" + count + "|" + moveBy[0] + "," + moveBy[1] + ","
                + moveBy[2] + "|" + box.getPos1() + "|" + box.getPos2();
    }

    /**
     * Counts both rows against the schematic as it is, and shows the live one in the world.
     * Guarded on {@link #key}, so a frame where nothing moved costs one string.
     */
    private void sync() {
        String key = key();

        if (key.equals(syncedFor)) {
            return;
        }

        clearPreview();
        syncedFor = key;

        if (key.isEmpty()) {
            stackPlan = movePlan = null;
            return;
        }

        Box box = box();
        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();
        boolean showStack = previewOn && live == Live.STACK;
        boolean showMove = previewOn && live == Live.MOVE && !moveIsZero();

        // The row not on show is counted first, while the schematic is still as it was.
        stackPlan = showStack ? null
                : BoxEdit.plan(schematic, placement, pos1, pos2, stackOffsets(), false, airReplaces);
        movePlan = moveIsZero() || showMove ? null
                : BoxEdit.plan(schematic, placement, pos1, pos2, moveOffsets(), true, airReplaces);

        if (showStack || showMove) {
            BoxEdit.Plan shown = preview.show(schematic, placement, pos1, pos2,
                    showStack ? stackOffsets() : moveOffsets(), showMove, airReplaces);

            if (showStack) {
                stackPlan = shown;
            } else {
                movePlan = shown;
            }
        }

        boolean nothing = stackPlan.doesNothing();
        stackButton.setEnabled(!nothing);
        stackButton.setDisplayString(nothing ? "Nothing in the box"
                : "Stack " + count + (count == 1 ? " copy" : " copies"));

        moveButton.setEnabled(movePlan != null && !movePlan.doesNothing());
        moveButton.setDisplayString(nothing ? "Nothing in the box"
                : "Move " + stackPlan.blocks() + " blocks");
    }

    /**
     * Takes the preview off, if one is on, and puts the dirty flag back with it. Called before
     * anything measures the schematic, before each re-preview, and from every exit.
     */
    private void clearPreview() {
        preview.clear();
        syncedFor = "";
    }

    @Override
    public void removed() {
        // The backstop: Escape and anything else that takes the screen away without a button.
        clearPreview();
        super.removed();
    }

    /**
     * Stack and Move go through here: straight through when every seam settled, or to the review
     * when something would not survive - the change is not kept until that is answered.
     */
    private void onStack(ButtonBase button, int mouseButton) {
        if (stackPlan != null && !stackPlan.wontSurvive().isEmpty()) {
            review(Live.STACK, stackPlan);
        } else {
            doStack(false);
        }
    }

    private void onMove(ButtonBase button, int mouseButton) {
        if (movePlan != null && !movePlan.wontSurvive().isEmpty()) {
            review(Live.MOVE, movePlan);
        } else {
            doMove(false);
        }
    }

    private void review(Live which, BoxEdit.Plan plan) {
        reviewing = which;
        reviewPlan = plan;
        this.initGui();
    }

    private void doStack(boolean dropDoomed) {
        Box box = box();

        if (box == null) {
            return;
        }

        // Off and on again rather than adopted: the real change goes on the undo stack, and the
        // preview never did.
        clearPreview();
        String label = "Stack " + count + " " + toward.getSerializedName();
        commit(BoxEdit.apply(schematic, placement, box.getPos1(), box.getPos2(), stackOffsets(),
                false, airReplaces, dropDoomed, label));
    }

    private void doMove(boolean dropDoomed) {
        Box box = box();
        AreaSelection selection = selection();

        if (box == null || selection == null || moveIsZero()) {
            return;
        }

        clearPreview();
        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();
        Vec3i by = new Vec3i(moveBy[0], moveBy[1], moveBy[2]);
        BoxEdit.Plan done = BoxEdit.apply(schematic, placement, pos1, pos2, moveOffsets(), true,
                airReplaces, dropDoomed, "Move by " + by.toShortString());

        if (!done.doesNothing()) {
            // The outline follows what it was drawn around.
            selection.setSubRegionCornerPos(box, Corner.CORNER_1, pos1.offset(by));
            selection.setSubRegionCornerPos(box, Corner.CORNER_2, pos2.offset(by));
        }

        commit(done);
    }

    private void commit(BoxEdit.Plan done) {
        if (!done.doesNothing()) {
            Pending.recordSwap(schematic);
            dev.matt.letmereplace.Recount.later(materialList);
        }

        this.closeGui(true);
    }

    /** Gold when something would not survive, so the count is seen before the button is. */
    private static int tone(BoxEdit.Plan plan) {
        return plan.wontSurvive().isEmpty() ? Ui.GREY : Ui.GOLD;
    }

    private static String describe(BoxEdit.Plan plan) {
        Vec3i after = plan.sizeAfter();
        String size = plan.grows()
                ? "grows to " + after.getX() + "x" + after.getY() + "x" + after.getZ()
                : "no growth";

        return plan.blocks() + " blocks" + (plan.entities() > 0 ? ", " + plan.entities() + " entities" : "")
                + " - " + size + " - overwrites " + plan.overwrites()
                + (plan.seamsFixed() > 0 ? " - fixes " + plan.seamsFixed() : "")
                + (plan.wontSurvive().isEmpty() ? "" : " - " + plan.wontSurvive().size()
                        + " won't survive");
    }

    @Override
    protected void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        if (reviewing != null) {
            int doomed = reviewPlan.wontSurvive().size();
            this.drawString(context, doomed + (doomed == 1 ? " block" : " blocks")
                    + " would not survive here", 10, 36, Ui.GOLD);
            int y = 52;

            for (String line : doomedLines(reviewPlan)) {
                this.drawString(context, line, 18, y, Ui.WHITE);
                y += 12;
            }

            this.drawString(context, "Keep them as they are, or remove them with the "
                    + (reviewing == Live.STACK ? "stack" : "move") + ".", 10, y + 6, Ui.GREY);
            return;
        }

        if (problem != null) {
            this.drawString(context, problem, 10, 36, Ui.GOLD);
            return;
        }

        sync();

        this.drawString(context, "From", 10, boxY + 4, Ui.WHITE);
        this.drawString(context, "To", 10, boxY + 26, Ui.WHITE);
        // The row on show in the world is the coloured one.
        boolean shown = preview.isShowing();
        this.drawString(context, "Stack", 10, stackY + 4,
                shown && live == Live.STACK ? Ui.AQUA : Ui.WHITE);
        this.drawString(context, "Move", 10, moveY + 4,
                shown && live == Live.MOVE ? Ui.AQUA : Ui.WHITE);
        this.drawString(context, "by", 50, moveY + 4, Ui.GREY);

        int stepLabelX = 10 + 40 + 32 + 4 + 80 + 8;
        this.drawString(context, "step", stepLabelX, stackY + 4, Ui.GREY);

        if (stackPlan != null) {
            this.drawString(context, describe(stackPlan), 50, stackY + 21, tone(stackPlan));
        }

        if (movePlan != null) {
            this.drawString(context, describe(movePlan), 50, moveY + 21, tone(movePlan));
        }
    }
}
