package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Axis;
import dev.matt.letmereplace.Census;
import dev.matt.letmereplace.Commit;
import dev.matt.letmereplace.Palette;
import dev.matt.letmereplace.Swap;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Change a whole variant axis at once - every oak to spruce, every copper one stage further on.
 *
 * <p>Ticket 006 A12: one screen, not a wizard. This is the project whose defining bug was too
 * many screens in a chain, so axis, source and target are three columns on one screen with the
 * preview grid updating as you choose.
 */
public class PaletteScreen extends GuiBase {

    private final LitematicaSchematic schematic;
    private final MaterialListBase materialList;
    private final Census census;
    private final List<Palette.Present> present;

    private Palette.Present axis;
    private String source = Palette.ALL;
    private String target;

    /** The stone axis has 50-odd values, so the value columns scroll and filter. */
    private int sourceScroll;
    private int targetScroll;
    private String sourceQuery = "";
    private String targetQuery = "";
    private GuiTextFieldGeneric sourceSearch;
    private GuiTextFieldGeneric targetSearch;

    /** 1 or 2 while a search field is being typed in, so focus survives the rebuild. */
    private int focusedColumn;

    /**
     * A text change cannot rebuild the screen where it happens - that would mutate the widget
     * lists malilib is in the middle of dispatching input through. Rebuilt on the next frame.
     */
    private boolean needsRebuild;

    public PaletteScreen(LitematicaSchematic schematic, MaterialListBase materialList) {
        this.schematic = schematic;
        this.materialList = materialList;
        this.census = Census.of(schematic);
        this.present = Palette.present(census);
        this.useTitleHierarchy = false;
        this.setTitle("Change palette");

        if (!present.isEmpty()) {
            selectAxis(present.get(0));
        }
    }

    private void selectAxis(Palette.Present chosen) {
        this.axis = chosen;
        this.source = Palette.ALL;
        this.target = null;
        this.sourceScroll = 0;
        this.targetScroll = 0;
        this.sourceQuery = "";
        this.targetQuery = "";

        // Default the target to the first value the schematic is not already entirely on.
        for (String value : chosen.axis().values()) {
            if (!chosen.valuesPresent().contains(value) || chosen.valuesPresent().size() > 1) {
                this.target = value;
                break;
            }
        }

        if (this.target == null) {
            this.target = chosen.axis().values().get(0);
        }
    }

    // ------------------------------------------------------------------------ layout

    private int columnWidth() {
        return Math.max(90, (this.width - Ui.MARGIN * 2 - 40) / 3);
    }

    private int columnX(int index) {
        return Ui.MARGIN + 10 + index * (columnWidth() + 10);
    }

    private int columnTop() {
        return Ui.MARGIN + Ui.TITLE_H + 14;
    }

    private int gridTop() {
        return this.height - Ui.MARGIN - 78;
    }

    @Override
    public void initGui() {
        super.initGui();

        if (present.isEmpty()) {
            this.addButton(new ButtonGeneric(this.width / 2 - 50, this.height / 2 + 20, 100, 20,
                    "Back"), (button, mouseButton) -> this.closeGui(true));
            return;
        }

        int y = valuesTop();

        // A6: only axes the schematic is actually on. Presence is a fact the census already knows.
        for (Palette.Present option : present) {
            ButtonGeneric button = new ButtonGeneric(columnX(0), y, columnWidth(), 16,
                    option.axis().name() + "  (" + option.total() + ")");
            button.setEnabled(option != axis);
            this.addButton(button, (b, m) -> {
                selectAxis(option);
                this.initGui();
            });
            y += 18;
        }

        // A7: the source defaults to every value present, so "make it all spruce" is one click,
        // while "convert just the oak trim" is still expressible.
        sourceSearch = addSearchField(1, sourceQuery, text -> {
            sourceQuery = text;
            focusedColumn = 1;
            sourceScroll = 0;
        });
        targetSearch = addSearchField(2, targetQuery, text -> {
            targetQuery = text;
            focusedColumn = 2;
            targetScroll = 0;
        });

        List<String> sources = new ArrayList<>();
        sources.add(Palette.ALL);
        sources.addAll(axis.valuesPresent());
        sources = matching(sources, sourceQuery);
        sourceScroll = clampScroll(sourceScroll, sources.size());

        y = valuesTop();

        for (String value : window(sources, sourceScroll)) {
            String label = Palette.ALL.equals(value)
                    ? "All present  (" + axis.total() + ")"
                    : Axis.pretty(value) + "  (" + axis.valueCounts().get(value) + ")";
            y = addValueButton(1, y, value, label, () -> source = value, value.equals(source));
        }

        y = valuesTop();
        List<String> targets = matching(targetValues(), targetQuery);

        if (!targets.contains(target)) {
            target = targets.isEmpty() ? null : targets.get(0);
        }

        targetScroll = clampScroll(targetScroll, targets.size());

        for (String value : window(targets, targetScroll)) {
            y = addValueButton(2, y, value, Axis.pretty(value),
                    () -> target = value, value.equals(target));
        }

        int buttonY = this.height - Ui.MARGIN - 24;
        Palette.Change change = currentChange();

        ButtonGeneric apply = new ButtonGeneric(this.width / 2 - 104, buttonY, 100, 20,
                change.isEmpty() ? "Nothing to change" : "Change " + change.pairs().size() + " blocks");
        apply.setEnabled(!change.isEmpty());
        this.addButton(apply, this::onApply);

        this.addButton(new ButtonGeneric(this.width / 2 + 4, buttonY, 100, 20, "Cancel"),
                (button, mouseButton) -> this.closeGui(true));
    }

    private int addValueButton(int column, int y, String value, String label,
                               Runnable onPick, boolean selected) {
        ButtonGeneric button = new ButtonGeneric(columnX(column), y, columnWidth(), 16, label);
        button.setEnabled(!selected);
        this.addButton(button, (b, m) -> {
            onPick.run();
            this.initGui();
        });
        return y + 18;
    }

    /**
     * A9-adjacent, asked for at the keyboard: converting <em>from</em> plain prismarine should not
     * offer plain prismarine as a destination. With the source set to everything present there is
     * nothing single to exclude, so the whole list stands.
     */
    private List<String> withAll(List<String> values) {
        List<String> out = new ArrayList<>();
        out.add(Palette.ALL);
        out.addAll(values);
        return out;
    }

    private List<String> targetValues() {
        List<String> values = new ArrayList<>(axis.axis().values());

        if (!Palette.ALL.equals(source)) {
            values.remove(source);
        }

        return values;
    }

    /** Value rows start below the search field. */
    private int valuesTop() {
        return columnTop() + 20;
    }

    /** How many value rows fit between the search field and the preview grid. */
    private int rowsPerColumn() {
        return Math.max(3, (gridTop() - 16 - valuesTop()) / 18);
    }

    private GuiTextFieldGeneric addSearchField(int column, String text,
                                               java.util.function.Consumer<String> onChange) {
        GuiTextFieldGeneric field =
                new GuiTextFieldGeneric(columnX(column), columnTop(), columnWidth(), 16, this.font);
        field.setValueWrapper(text);

        this.addTextField(field, changed -> {
            onChange.accept(changed.getValue());
            needsRebuild = true;
            return true;
        });

        if (focusedColumn == column) {
            field.setFocusedWrapper(true);
        }

        return field;
    }

    /** Case-insensitive substring, over both the pretty label and the raw id fragment. */
    private List<String> matching(List<String> values, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);

        if (needle.isEmpty()) {
            return values;
        }

        List<String> out = new ArrayList<>();

        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains(needle)
                    || Axis.pretty(value).toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(value);
            }
        }

        return out;
    }

    private List<String> window(List<String> values, int scroll) {
        int from = Math.min(scroll, Math.max(0, values.size() - 1));
        return values.subList(from, Math.min(values.size(), from + rowsPerColumn()));
    }

    private int clampScroll(int scroll, int size) {
        return Math.max(0, Math.min(scroll, Math.max(0, size - rowsPerColumn())));
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal,
                                   double vertical) {
        if (present.isEmpty() || vertical == 0) {
            return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        int step = vertical > 0 ? -1 : 1;

        if (mouseX >= columnX(1) && mouseX < columnX(1) + columnWidth()) {
            sourceScroll += step;
            this.initGui();
            return true;
        }

        if (mouseX >= columnX(2) && mouseX < columnX(2) + columnWidth()) {
            targetScroll += step;
            this.initGui();
            return true;
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private Palette.Change currentChange() {
        return Palette.change(census, axis.axis(), source, target);
    }

    private void onApply(ButtonBase button, int mouseButton) {
        Palette.Change change = currentChange();
        Swap.Plan plan = Swap.planPairs(schematic, census, Palette.asBlockMap(change));

        List<String> notes = new ArrayList<>();

        // A8: name what has no counterpart rather than burying it in a total.
        for (Block block : change.noCounterpart()) {
            notes.add("no " + Axis.pretty(target) + " counterpart: " + Axis.path(block));
        }

        String title = axis.axis().name() + ": "
                + (Palette.ALL.equals(source) ? "all" : Axis.pretty(source))
                + " to " + Axis.pretty(target);

        // The parent, never `this` - ticket 004 D1 applies to this screen like any other.
        Screens.open(new ConfirmScreen(
                new Commit(title, schematic, materialList, plan, notes)), this.getParent());
    }

    // ----------------------------------------------------------------------- painting

    @Override
    protected void drawScreenBackground(GuiContext context, int mouseX, int mouseY) {
        if (needsRebuild) {
            needsRebuild = false;
            this.initGui();
        }

        RenderUtils.drawRect(context, 0, 0, this.width, this.height, Ui.SHADE);

        int w = this.width - Ui.MARGIN * 2;
        int h = this.height - Ui.MARGIN * 2;

        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN, w, h, Ui.PANEL);
        RenderUtils.drawOutline(context, Ui.MARGIN - 1, Ui.MARGIN - 1, w + 2, h + 2, Ui.EDGE);
        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN, w, Ui.TITLE_H, Ui.HEADER_BG);
        RenderUtils.drawRect(context, Ui.MARGIN, gridTop() - 4, w, 52, Ui.PANEL_ALT);
    }

    @Override
    protected void drawTitle(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        this.drawString(context, this.getTitleString(), Ui.MARGIN + 5, Ui.MARGIN + 4, Ui.WHITE);

        if (present.isEmpty()) {
            this.drawString(context, "Nothing in this schematic sits on a changeable axis.",
                    Ui.MARGIN + 10, this.height / 2 - 10, Ui.GREY);
            return;
        }

        this.drawString(context, "AXIS", columnX(0), columnTop() - 11, Ui.GREY);
        drawColumnHead(context, 1, "FROM",
                matching(withAll(axis.valuesPresent()), sourceQuery).size(), sourceScroll);
        drawColumnHead(context, 2, "TO",
                matching(targetValues(), targetQuery).size(), targetScroll);

        drawPreviewGrid(context);
    }

    /** The header, plus how many values are scrolled out of view above and below. */
    private void drawColumnHead(GuiContext context, int column, String title, int total,
                                int scroll) {
        this.drawString(context, title, columnX(column), columnTop() - 11, Ui.GREY);

        int shown = Math.min(rowsPerColumn(), Math.max(0, total - scroll));
        int below = Math.max(0, total - scroll - shown);

        if (scroll > 0 || below > 0) {
            String note = (scroll > 0 ? "\u25b2" + scroll + " " : "") + (below > 0 ? "\u25bc" + below : "");
            this.drawString(context, note.trim(),
                    columnX(column) + columnWidth() - this.getStringWidth(note.trim()),
                    columnTop() - 11, Ui.GOLD);
        }
    }

    /**
     * A10: a before-to-after grid of item icons. "Oak to spruce" is abstract; a row of planks,
     * stairs and doors changing is not, and it is the only place to catch "I didn't want the
     * signs touched" while there is still no undo to fall back on.
     */
    private void drawPreviewGrid(GuiContext context) {
        Palette.Change change = currentChange();
        int x = Ui.MARGIN + 8;
        int y = gridTop();

        if (change.isEmpty()) {
            this.drawString(context, "No block would change.", x, y + 8, Ui.GREY);
            return;
        }

        this.drawString(context, change.pairs().size() + " blocks change"
                + (change.noCounterpart().isEmpty()
                        ? ""
                        : ", " + change.noCounterpart().size() + " have no counterpart"),
                x, y - 12, change.noCounterpart().isEmpty() ? Ui.GREEN : Ui.GOLD);

        int perRow = Math.max(1, (this.width - Ui.MARGIN * 2 - 16) / 40);

        for (int i = 0; i < change.pairs().size(); ++i) {
            if (i >= perRow * 2) {
                this.drawString(context, "+" + (change.pairs().size() - i) + " more",
                        x + (i % perRow) * 40, y + (i / perRow) * 20 + 4, Ui.GREY);
                break;
            }

            Palette.Pair pair = change.pairs().get(i);
            int px = x + (i % perRow) * 40;
            int py = y + (i / perRow) * 20;

            // Item icons, not block models - ticket 005's amendment applies here for the same
            // reason: these are many small icons, and PictureInPicture is a one-at-a-time thing.
            drawIconAt(context, pair.from(), px, py);
            this.drawString(context, ">", px + 17, py + 4, Ui.GREY);
            drawIconAt(context, pair.to(), px + 23, py);
        }
    }

    private void drawIconAt(GuiContext context, Block block, int x, int y) {
        ItemStack stack = block.asItem() instanceof BlockItem item
                ? new ItemStack(item)
                : ItemStack.EMPTY;

        if (stack.isEmpty()) {
            RenderUtils.drawRect(context, x + 4, y + 4, 8, 8, Ui.GREY);
        } else {
            InventoryOverlay.renderStackAt(context, stack, x, y, 0.75f);
        }
    }
}
