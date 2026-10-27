package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.BoxEdit;
import dev.matt.letmereplace.LetMeReplace;
import dev.matt.letmereplace.Pending;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.matt.letmereplace.Settings;
import dev.matt.letmereplace.UndoLog;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stacking and moving with the scroll wheel, in the world - ticket 007's in-world half.
 *
 * <p>Two ways in, one mode behind both. <b>Hold</b> the Stack or Move key and scroll, or press
 * "Stack in world..." / "Move in world..." on the box screen, which arms the same mode
 * <b>latched</b> so the wheel works without a key. Either way the result is only a preview until
 * Litematica's own <b>Execute operation</b> hotkey prints it into the schematic - the way
 * Litematica's Fill, Replace and Paste modes work (asked for at the keyboard 2026-10-01; it used to
 * keep on release). Letting go of the key leaves it pending, and holding it again adds to it.
 * Escape, any screen opening, or leaving the tool mode takes it off. A screen has to cancel it:
 * a Save from the material list must never write a preview nobody executed.
 *
 * <p>The box is Litematica's selected area box, and the schematic is the placement under it. Each
 * notch re-applies the <em>total</em> from the untouched schematic through the same
 * {@link LivePreview} the box screen uses, so nothing accumulates between notches and what is in
 * the world is exactly what Keep keeps.
 *
 * <ul>
 *   <li><b>Stack</b>: scroll up adds a copy on the side you are looking at, snapped to the nearest
 *       of the six; scroll down takes one back off that side. Copies can go out on several sides.
 *   <li><b>Move</b>: each notch moves the contents one block toward where you look, or one box
 *       length while sneaking.
 * </ul>
 *
 * <p>The wheel belongs to the mode while it is armed by a hook at the head of Litematica's own
 * scroll handler - see {@code LitematicaInputHandlerMixin} - rather than by registering a second
 * handler and hoping it runs first. The panel under Litematica's tool info is
 * {@link InfoHud}'s public renderer list, so no HUD code of ours either.
 *
 * <p><b>Litematica's tool.</b> The keys only work while the tool is held in Litematica's
 * <em>Grid Paste</em> mode, which 0.28.5 defines and puts in the ctrl+scroll cycle but never
 * implements - nothing else in the jar refers to it. Asked for at the keyboard 2026-10-01: that
 * slot becomes "Box in schematic", where left and right click set the box's corners on the
 * schematic block aimed at (real blocks are ignored) and the two keys plus the wheel stack and
 * move. Taking an unused slot rather than adding enum constants means no enum surgery, and
 * nothing breaks if LetMeReplace is removed. If a future Litematica implements Grid Paste, this
 * has to move.
 *
 * <p>The keys are malilib hotkeys in LetMeReplace's own settings screen - see {@link Settings} -
 * unbound by default. Being gated on the tool mode, they can share a key that means something
 * else the rest of the time.
 */
public final class WorldMode {

    public enum Kind { STACK, MOVE }

    /** What Grid Paste is called once it is ours. */
    public static final String MODE_NAME = "Box in schematic";

    /** How far a corner click reaches into the schematic, in blocks. */
    private static final double REACH = 200;

    private static final ConfigHotkey stackKey = Settings.STACK_BOX;
    private static final ConfigHotkey moveKey = Settings.MOVE_BOX;

    private static Kind kind;
    private static boolean latched;
    private static boolean reviewing;

    private static LitematicaSchematic schematic;
    private static SchematicPlacement placement;
    private static BlockPos lo;
    private static BlockPos hi;

    private static final Map<Direction, Integer> copies = new EnumMap<>(Direction.class);
    private static Vec3i moveBy = Vec3i.ZERO;

    private static final LivePreview preview = new LivePreview();

    /** Physical key state last tick, for press edges. */
    private static boolean stackWasDown;
    private static boolean moveWasDown;
    private static BoxEdit.Plan plan;

    private WorldMode() {
    }

    public static void register() {
        ((dev.matt.letmereplace.mixin.ToolModeAccessor) (Object) ToolMode.GRID_PASTE)
                .letmereplace$setCreativeOnly(false);

        ClientTickEvents.END_CLIENT_TICK.register(WorldMode::tick);
        InfoHud.getInstance().addInfoHudRenderer(new Panel(), true);
    }

    // ------------------------------------------------------------------------ arming

    /** Starts a mode on the selected box, or says why it cannot. */
    static boolean arm(Kind which, boolean latch) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();

        if (box == null || box.getPos1() == null || box.getPos2() == null) {
            say("Select a box with Litematica's area selection first");
            return false;
        }

        BlockPos boxLo = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
        BlockPos boxHi = PositionUtils.getMaxCorner(box.getPos1(), box.getPos2());
        SchematicPlacement under = placementUnder(boxLo, boxHi);

        if (under == null) {
            say("The selected box is not over a placed schematic");
            return false;
        }

        kind = which;
        latched = latch;
        reviewing = false;
        placement = under;
        schematic = under.getSchematic();
        lo = boxLo;
        hi = boxHi;
        copies.clear();
        moveBy = Vec3i.ZERO;
        plan = null;

        LetMeReplace.LOGGER.info("world mode: {} armed{} on {} .. {}", which,
                latch ? " (latched)" : "", lo, hi);
        return true;
    }

    /** Dev probe access to the same choice the mode makes. */
    static SchematicPlacement placementUnderForProbe(BlockPos boxLo, BlockPos boxHi) {
        return placementUnder(boxLo, boxHi);
    }

    /** The selected placement when the box is over it, otherwise the first one that is. */
    private static SchematicPlacement placementUnder(BlockPos boxLo, BlockPos boxHi) {
        var manager = DataManager.getSchematicPlacementManager();
        List<SchematicPlacement> candidates = new ArrayList<>();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();

        if (selected != null) {
            candidates.add(selected);
        }

        candidates.addAll(manager.getAllSchematicsPlacements());

        for (SchematicPlacement candidate : candidates) {
            // The live per-region world boxes, not getEclosingBox(): that one is a cached field
            // Litematica only refreshes on some paths, and a stale one said "not over a placed
            // schematic" with the box sitting squarely on the build.
            for (Box region : candidate.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY)
                    .values()) {
                if (region.getPos1() == null || region.getPos2() == null) {
                    continue;
                }

                BlockPos min = PositionUtils.getMinCorner(region.getPos1(), region.getPos2());
                BlockPos max = PositionUtils.getMaxCorner(region.getPos1(), region.getPos2());

                if (boxLo.getX() <= max.getX() && boxHi.getX() >= min.getX()
                        && boxLo.getY() <= max.getY() && boxHi.getY() >= min.getY()
                        && boxLo.getZ() <= max.getZ() && boxHi.getZ() >= min.getZ()) {
                    return candidate;
                }
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------ input

    /** From the head of Litematica's scroll handler. True takes the wheel from everyone else. */
    public static boolean onScroll(double amount) {
        Minecraft mc = Minecraft.getInstance();

        if (reviewing || GuiUtils.getCurrentScreen() != null || mc.player == null || amount == 0) {
            return false;
        }

        // While one of our keys is held in our mode the wheel is ours even if the mode cannot
        // start: Litematica's own modifier+scroll - Alt is its nudge - would otherwise move the
        // placement instead.
        Kind held = !inMode() ? null
                : isDown(stackKey) ? Kind.STACK : isDown(moveKey) ? Kind.MOVE : null;

        if (kind == null) {
            // Armed here as well as on the tick, so the first notch after the press is not lost
            // to the 50 ms until the next tick.
            if (held == null) {
                return false;
            }

            if (!arm(held, false)) {
                return true;
            }
        } else if (!latched && held == null) {
            return false;                         // pending, key let go: the wheel is the hotbar's
        } else if (!latched && held != kind) {
            say("Execute or cancel the pending " + verb() + " first");
            return true;
        }

        int notch = amount > 0 ? 1 : -1;
        Direction look = Direction.getApproximateNearest(mc.player.getLookAngle());

        if (kind == Kind.STACK) {
            copies.merge(look, notch, Integer::sum);

            if (copies.get(look) <= 0) {
                copies.remove(look);
            }
        } else {
            int by = mc.player.isShiftKeyDown() ? BoxEdit.length(lo, hi, look) : 1;
            moveBy = moveBy.offset(look.getStepX() * by * notch, look.getStepY() * by * notch,
                    look.getStepZ() * by * notch);
        }

        refresh();
        return true;
    }

    /**
     * From the head of Litematica's click handler. In our tool mode, and while anything is
     * pending, left and right click are swallowed - press and release - so neither reaches
     * vanilla and breaks or uses whatever is behind the box. The corners come from Litematica's
     * corner hotkeys instead - see LitematicaToolHotkeysMixin - so they follow the bindings.
     */
    public static boolean onClick(int button, boolean pressed) {
        if (reviewing || GuiUtils.getCurrentScreen() != null || (kind == null && !inMode())) {
            return false;
        }

        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    /** From Litematica's Execute operation hotkey. True when there was something of ours to do. */
    public static boolean execute() {
        if (kind == null || reviewing || GuiUtils.getCurrentScreen() != null) {
            return false;
        }

        keep();
        return true;
    }

    private static void tick(Minecraft mc) {
        boolean stackDown = isDown(stackKey);
        boolean moveDown = isDown(moveKey);
        boolean stackPressed = stackDown && !stackWasDown;
        boolean movePressed = moveDown && !moveWasDown;
        stackWasDown = stackDown;
        moveWasDown = moveDown;

        if (kind == null) {
            // On the press edge only, so a failed arm says so once rather than every tick.
            if (GuiUtils.getCurrentScreen() == null && mc.player != null && inMode()) {
                if (stackPressed) {
                    arm(Kind.STACK, false);
                } else if (movePressed) {
                    arm(Kind.MOVE, false);
                }
            }

            return;
        }

        if (reviewing) {
            return;
        }

        if (GuiUtils.getCurrentScreen() != null || mc.player == null) {
            cancel();                  // Escape opened the pause menu, or anything else did
            return;
        }

        if (!latched && !inMode()) {
            if (!offsets().isEmpty()) {
                say(label() + ": cancelled, the tool left Box in schematic");
            }

            cancel();
            return;
        }

        // Pressed and let go without a single notch: nothing is pending, so nothing stays armed.
        boolean held = kind == Kind.STACK ? stackDown : moveDown;

        if (!latched && !held && offsets().isEmpty()) {
            reset();
        }
    }

    private static String verb() {
        return kind == Kind.STACK ? "stack" : "move";
    }

    private static String executeKeys() {
        return pretty(Hotkeys.EXECUTE_OPERATION.getKeybind().getKeysDisplayString());
    }

    /** "LEFT_CONTROL + LEFT_ALT + E" as a player writes it: "Ctrl+Alt+E". */
    static String pretty(String keys) {
        List<String> out = new ArrayList<>();

        for (String key : keys.split("\\s*\\+\\s*")) {
            String k = key.trim().replaceFirst("^(LEFT|RIGHT)_", "");
            k = switch (k) {
                case "CONTROL" -> "Ctrl";
                case "ALT" -> "Alt";
                case "SHIFT" -> "Shift";
                case "SUPER" -> "Super";
                default -> k.length() <= 1 ? k
                        : k.charAt(0) + k.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            };
            out.add(k);
        }

        return String.join("+", out);
    }

    /** Litematica's tool in hand, in the Grid Paste slot. */
    public static boolean inMode() {
        var player = Minecraft.getInstance().player;

        return player != null && DataManager.getToolMode() == ToolMode.GRID_PASTE
                && EntityUtils.hasToolItem(player);
    }

    /**
     * A corner on the schematic block aimed at. The trace is Litematica's schematic-world one, so
     * real blocks in front are looked through, and aiming anywhere but at the placement's blocks
     * - empty air included - changes nothing.
     */
    public static void setCorner(Corner corner) {
        if (kind != null) {
            say("Execute or cancel the pending " + verb() + " first");
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, REACH, true, false);

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            say("Aim at a block of the schematic");
            return;
        }

        BlockPos at = hit.getBlockPos();
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (selection == null) {
            say("No area selection to put the corner in");
            return;
        }

        Box box = selection.getSelectedSubRegionBox();

        if (box == null) {
            String name = selection.createNewSubRegionBox(at, selection.getName());

            if (name != null) {
                selection.setSelectedSubRegionBox(name);
            }

            box = selection.getSelectedSubRegionBox();
        }

        if (box == null) {
            say("Could not create a selection box");
            return;
        }

        selection.setSubRegionCornerPos(box, corner, at);
        say((corner == Corner.CORNER_1 ? "Corner 1" : "Corner 2") + ": " + at.getX() + " "
                + at.getY() + " " + at.getZ());
    }

    /**
     * Whether a hotkey is held, from malilib's own key state. Not vanilla's click counter or
     * {@code isDown}: malilib can take a key event before vanilla sees it when the key belongs to
     * one of its hotkeys - Left Alt is Litematica's nudge modifier - so a vanilla Stack key on Alt
     * never armed at all.
     */
    private static boolean isDown(ConfigHotkey hotkey) {
        return hotkey.getKeybind().isKeybindHeld();
    }

    private static String keyName(ConfigHotkey hotkey) {
        String keys = hotkey.getKeybind().getKeysDisplayString();
        return keys.isBlank() ? "(no key)" : pretty(keys);
    }

    /**
     * The undo hotkey. A pending stack or move is cancelled first and that is all - undo while
     * something is pending means "not that", not "and the change before it too".
     */
    public static boolean undoKey() {
        if (kind != null) {
            if (!offsets().isEmpty()) {
                say(label() + ": cancelled");
            }

            cancel();
            return true;
        }

        LitematicaSchematic target = UndoLog.lastEdited();
        String what = target == null ? null : UndoLog.label(target);

        int undone = dev.matt.letmereplace.History.undo(target);

        // Shared: the server answers on the action bar, with who undid what.
        if (undone != dev.matt.letmereplace.History.SENT) {
            say(undone > 0 ? "Undo: " + what : "Nothing to undo");
        }
        return true;
    }

    public static boolean redoKey() {
        if (kind != null) {
            say("Execute or cancel the pending " + verb() + " first");
            return true;
        }

        LitematicaSchematic target = UndoLog.lastEdited();
        String what = target == null ? null : UndoLog.redoLabel(target);

        int redone = dev.matt.letmereplace.History.redo(target);

        if (redone != dev.matt.letmereplace.History.SENT) {
            say(redone > 0 ? "Redo: " + what : "Nothing to redo");
        }
        return true;
    }

    // ------------------------------------------------------------------------ the edit

    private static List<Vec3i> offsets() {
        if (kind == Kind.MOVE) {
            return moveBy.equals(Vec3i.ZERO) ? List.of() : List.of(moveBy);
        }

        // A grid, not a cross: every axis contributes its own offsets - none, and each copy on
        // either side of it - and every combination of them is a tile. Scrolling up 2 then south
        // 3 repeats the whole column south, 3 x 4 tiles, rather than two separate arms. Asked for
        // at the keyboard 2026-10-01.
        List<List<Integer>> perAxis = new ArrayList<>();

        for (Direction.Axis axis : Direction.Axis.values()) {
            List<Integer> along = new ArrayList<>();
            along.add(0);

            for (Direction side : new Direction[] {
                    Direction.get(Direction.AxisDirection.POSITIVE, axis),
                    Direction.get(Direction.AxisDirection.NEGATIVE, axis)}) {
                int length = BoxEdit.length(lo, hi, side);
                int sign = side.getAxisDirection().getStep();

                for (int k = 1; k <= copies.getOrDefault(side, 0); ++k) {
                    along.add(sign * length * k);
                }
            }

            perAxis.add(along);
        }

        List<Vec3i> all = new ArrayList<>();

        for (int x : perAxis.get(0)) {
            for (int y : perAxis.get(1)) {
                for (int z : perAxis.get(2)) {
                    if (x != 0 || y != 0 || z != 0) {
                        all.add(new Vec3i(x, y, z));
                    }
                }
            }
        }

        return all;
    }

    private static void refresh() {
        List<Vec3i> offsets = offsets();

        if (offsets.isEmpty()) {
            preview.clear();
            plan = null;
            return;
        }

        plan = preview.show(schematic, placement, lo, hi, offsets, kind == Kind.MOVE,
                BoxScreen.airReplaces);
    }

    /** Keeps what is showing, unless something would not survive - then that is asked first. */
    private static void keep() {
        if (plan != null && !plan.wontSurvive().isEmpty()) {
            reviewing = true;
            GuiBase.openGui(new Review(plan));
            return;
        }

        commit(false);
    }

    private static void commit(boolean dropDoomed) {
        List<Vec3i> offsets = offsets();
        Kind done = kind;
        preview.clear();

        if (!offsets.isEmpty()) {
            BoxEdit.Plan result = BoxEdit.apply(schematic, placement, lo, hi, offsets,
                    done == Kind.MOVE, BoxScreen.airReplaces, dropDoomed, label());

            if (!result.doesNothing()) {
                Pending.recordSwap(schematic);

                if (placement.getMaterialList() != null) {
                    dev.matt.letmereplace.Recount.later(placement.getMaterialList());
                }

                if (done == Kind.MOVE) {
                    moveSelection();
                }

                say(label() + ": kept");
            }
        }

        reset();
    }

    private static void cancel() {
        preview.clear();
        reset();
    }

    private static void reset() {
        kind = null;
        latched = false;
        reviewing = false;
        schematic = null;
        placement = null;
        plan = null;
        copies.clear();
        moveBy = Vec3i.ZERO;
    }

    /** The outline follows what it was drawn around, as on the box screen. */
    private static void moveSelection() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();

        if (box != null && box.getPos1() != null && box.getPos2() != null) {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();
            selection.setSubRegionCornerPos(box, Corner.CORNER_1, pos1.offset(moveBy));
            selection.setSubRegionCornerPos(box, Corner.CORNER_2, pos2.offset(moveBy));
        }
    }

    /** "Stack 4 east, 1 up" or "Move by 0 0 12". */
    private static String label() {
        if (kind == Kind.MOVE) {
            return "Move by " + moveBy.getX() + " " + moveBy.getY() + " " + moveBy.getZ();
        }

        List<String> sides = new ArrayList<>();

        for (Map.Entry<Direction, Integer> side : copies.entrySet()) {
            sides.add(side.getValue() + " " + side.getKey().getSerializedName());
        }

        int tiles = offsets().size();

        return "Stack " + (sides.isEmpty() ? "nothing" : String.join(", ", sides)
                + (sides.size() > 1 ? " (" + tiles + " copies)" : ""));
    }

    private static void say(String message) {
        var player = Minecraft.getInstance().player;

        if (player != null) {
            player.sendOverlayMessage(Component.literal(message));
        }
    }

    // ------------------------------------------------------------------------ HUD

    /** The lines under Litematica's tool info while a mode is armed. */
    private static final class Panel implements IInfoHudRenderer {

        @Override
        public boolean getShouldRenderText(RenderPhase phase) {
            return (kind != null || inMode()) && phase == RenderPhase.POST;
        }

        @Override
        public List<String> getText(RenderPhase phase) {
            List<String> lines = new ArrayList<>();

            // Short lines: this shares the bottom of the screen with Litematica's tool HUD, and at
            // GUI scale 4 long ones ran into it and the hotbar. Litematica already names the mode.
            if (kind == null) {
                lines.add("L / R click: box corners");
                lines.add(keyName(stackKey) + " + scroll: stack");
                lines.add(keyName(moveKey) + " + scroll: move");
                lines.add(executeKeys() + ": execute");
                return lines;
            }

            lines.add((kind == Kind.STACK ? "Stack" : "Move") + (plan != null ? " - pending" : ""));
            lines.add(label() + ", " + (hi.getX() - lo.getX() + 1) + "x" + (hi.getY() - lo.getY() + 1)
                    + "x" + (hi.getZ() - lo.getZ() + 1));

            if (plan != null) {
                Vec3i after = plan.sizeAfter();
                lines.add(plan.grows() ? "grows to " + after.getX() + "x" + after.getY() + "x"
                        + after.getZ() : "no growth");
                lines.add("overwrites " + plan.overwrites() + ", air "
                        + (BoxScreen.airReplaces ? "replaces" : "skipped"));

                if (!plan.wontSurvive().isEmpty()) {
                    lines.add(plan.wontSurvive().size() + " won't survive");
                }
            } else {
                lines.add(kind == Kind.STACK ? "scroll up: add a copy" : "scroll: move where you look");
            }

            if (kind == Kind.MOVE) {
                lines.add("sneak: a box length");
            }

            lines.add((latched ? "" : keyName(kind == Kind.STACK ? stackKey : moveKey) + " + ")
                    + "scroll: change");
            lines.add(executeKeys() + ": execute, Esc: cancel");
            return lines;
        }
    }

    // ------------------------------------------------------------------------ review

    /**
     * The would-not-survive list, over the world with no parent. Back returns to scrolling,
     * latched, since the key that was held is no longer down.
     */
    private static final class Review extends GuiBase {

        private final BoxEdit.Plan plan;
        private boolean decided;

        Review(BoxEdit.Plan plan) {
            this.plan = plan;
            this.useTitleHierarchy = false;
            this.setTitle("Seams");
        }

        @Override
        public void initGui() {
            super.initGui();

            int y = this.height - 40;

            this.addButton(new ButtonGeneric(10, y, 100, 20, "Keep them"),
                    (button, mouseButton) -> decide(() -> commit(false)));
            this.addButton(new ButtonGeneric(114, y, 100, 20, "Remove them"),
                    (button, mouseButton) -> decide(() -> commit(true)));
            this.addButton(new ButtonGeneric(218, y, 110, 20, "Back to scrolling"),
                    (button, mouseButton) -> decide(() -> reviewing = false));
            this.addButton(new ButtonGeneric(332, y, 70, 20, "Cancel"),
                    (button, mouseButton) -> decide(WorldMode::cancel));
        }

        private void decide(Runnable then) {
            decided = true;
            GuiBase.openGui(null);
            then.run();
        }

        @Override
        public void removed() {
            // Escape, or anything else that closes this without an answer, is Cancel.
            if (!decided) {
                decided = true;
                cancel();
            }

            super.removed();
        }

        @Override
        protected void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
            int doomed = plan.wontSurvive().size();
            this.drawString(context, doomed + (doomed == 1 ? " block" : " blocks")
                    + " would not survive here", 10, 36, Ui.GOLD);
            int y = 52;

            for (String line : BoxScreen.doomedLines(plan)) {
                this.drawString(context, line, 18, y, Ui.WHITE);
                y += 12;
            }
        }
    }
}
