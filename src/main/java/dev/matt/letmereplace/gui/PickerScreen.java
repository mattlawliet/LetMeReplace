package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Candidate;
import dev.matt.letmereplace.Commit;
import dev.matt.letmereplace.Carry;
import dev.matt.letmereplace.Session;
import dev.matt.letmereplace.Swap;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pick the replacement block.
 *
 * <p>Every candidate is offered (ticket 005 P1). Legality turned out to be a property of the
 * <em>state</em> rather than the block - a hopper row covers ten states that split 825 against
 * 1054 legal targets depending on {@code facing} - so a row has no binary legal answer and
 * greying one out would be a lie. Rows that would leave positions behind are flagged instead.
 */
public class PickerScreen extends GuiListBase<Candidate, WidgetCandidate, CandidateList> {

    private static final int LIST_Y = Ui.MARGIN + Ui.TITLE_H + Ui.SEARCH_H;

    private final Session session;

    /** Per-candidate results, computed lazily and kept for the life of the screen. */
    private final Map<Block, BlockState> previews = new IdentityHashMap<>();
    private final Map<Block, int[]> counts = new IdentityHashMap<>();

    private String lastQuery;
    private List<Candidate> lastRanked = List.of();
    private Candidate selected;

    public PickerScreen(Session session) {
        super(Ui.MARGIN + 2, LIST_Y);
        this.session = session;
        this.useTitleHierarchy = false;
        this.setTitle("Replace " + session.rowName());
    }

    @Override
    protected CandidateList createListWidget(int listX, int listY) {
        return new CandidateList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserWidth() {
        return this.width - (Ui.MARGIN * 2) - previewWidth() - 6;
    }

    @Override
    protected int getBrowserHeight() {
        return this.height - LIST_Y - Ui.MARGIN - Ui.FOOTER_H;
    }

    private int previewWidth() {
        return Math.min(190, Math.max(130, this.width / 4));
    }

    @Override
    public void initGui() {
        super.initGui();
        // P6: the field takes focus on open, which is what makes a five-material conversion
        // typeable rather than clickable.
        this.getListWidget().getSearchBarWidget().setSearchOpen(true);
    }

    // ---------------------------------------------------------------- data for the rows

    List<Candidate> rankedFor(String query) {
        String needle = query == null ? "" : query;

        if (!needle.equals(lastQuery)) {
            lastQuery = needle;
            lastRanked = Candidate.ranked(needle, session.modalState().getBlock());
        }

        return lastRanked;
    }

    /** The source's most common state carried onto this candidate, or null when it would skip. */
    BlockState previewOf(Candidate candidate) {
        return previews.computeIfAbsent(candidate.block(),
                block -> Carry.onto(session.modalState(), block));
    }

    int[] countsFor(Candidate candidate) {
        return counts.computeIfAbsent(candidate.block(),
                block -> Swap.quickCounts(session.sourceStates(), block));
    }

    void onSelectionChanged(Candidate candidate) {
        this.selected = candidate;
    }

    void pick(Candidate candidate) {
        if (candidate == null) {
            return;
        }

        Swap.Plan plan = Swap.plan(session.schematic(), session.sourceStates(), candidate.block());
        String title = "Replace " + session.rowName() + " with " + candidate.displayName();

        // The parent, never `this`: a LetMeReplace screen must not parent another one.
        Screens.open(new ConfirmScreen(Commit.of(title, session, plan)), this.getParent());
    }

    @Override
    public boolean onKeyTyped(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) {          // Enter, numpad Enter
            pick(this.getListWidget().topEntry());
            return true;
        }

        return super.onKeyTyped(event);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void drawScreenBackground(GuiContext context, int mouseX, int mouseY) {
        RenderUtils.drawRect(context, 0, 0, this.width, this.height, Ui.SHADE);

        int w = this.width - Ui.MARGIN * 2;
        int h = this.height - Ui.MARGIN * 2;

        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN, w, h, Ui.PANEL);
        RenderUtils.drawOutline(context, Ui.MARGIN - 1, Ui.MARGIN - 1, w + 2, h + 2, Ui.EDGE);
        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN, w, Ui.TITLE_H, Ui.HEADER_BG);
        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN + Ui.TITLE_H, w, Ui.SEARCH_H, Ui.PANEL_ALT);
        RenderUtils.drawRect(context, Ui.MARGIN, Ui.MARGIN + h - Ui.FOOTER_H, w, Ui.FOOTER_H, Ui.HEADER_BG);

        int panelX = Ui.MARGIN + w - previewWidth();
        RenderUtils.drawRect(context, panelX, LIST_Y, previewWidth(),
                h - Ui.TITLE_H - Ui.SEARCH_H - Ui.FOOTER_H, Ui.PANEL_ALT);
        RenderUtils.drawRect(context, panelX - 1, LIST_Y, 1,
                h - Ui.TITLE_H - Ui.SEARCH_H - Ui.FOOTER_H, Ui.EDGE);
    }

    @Override
    protected void drawTitle(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        this.drawString(context, this.getTitleString(), Ui.MARGIN + 5, Ui.MARGIN + 4, Ui.WHITE);

        String scope = session.totalPositions() + " positions";
        this.drawString(context, scope,
                Ui.MARGIN + this.width - Ui.MARGIN * 2 - 5 - this.getStringWidth(scope),
                Ui.MARGIN + 4, Ui.GREY);

        drawPreviewPanel(context);
        drawFooter(context);
    }

    /**
     * P8: the large render is where P5's honesty actually lands - a 16px icon cannot be read as
     * a <em>state</em>. The panel names which source state it is showing, so "most common" is
     * never a silent assumption.
     */
    private void drawPreviewPanel(GuiContext context) {
        Candidate candidate = selected != null ? selected : firstRanked();

        if (candidate == null) {
            return;
        }

        int x = Ui.MARGIN + (this.width - Ui.MARGIN * 2) - previewWidth() + 6;
        int y = LIST_Y + 6;
        int maxWidth = previewWidth() - 12;

        this.drawString(context, trim(candidate.displayName(), maxWidth), x, y, Ui.AQUA);
        y += 12;
        this.drawString(context, trim(candidate.id(), maxWidth), x, y, Ui.GREY);
        y += 16;

        BlockState result = previewOf(candidate);

        if (result != null && RenderUtils.stateModelHasQuads(result)) {
            // One element per frame, which is what PictureInPicture is built for. Size is the
            // only thing changed from malilib's tuned call - scale 0.75 and yOffset 0.5 are kept
            // exactly, so the model sits in its box at double scale instead of overflowing it.
            RenderUtils.renderModelInGui(context, x + maxWidth / 2 - 16, y, 32, result, 0.75f, 0.5f);
        }

        y += 44;

        this.drawString(context, "FROM", x, y, Ui.GREY);
        y += 10;
        this.drawString(context, trim(describe(session.modalState()), maxWidth), x, y, Ui.WHITE);
        y += 14;

        this.drawString(context, "TO", x, y, Ui.GREY);
        y += 10;

        if (result == null) {
            this.drawString(context, "no legal state", x, y, Ui.RED);
        } else {
            this.drawString(context, trim(describe(result), maxWidth), x, y, Ui.WHITE);
        }

        y += 18;

        int[] counted = countsFor(candidate);
        String verdict = counted[1] == 0
                ? "converts all " + counted[0]
                : "converts " + counted[0] + ", skips " + counted[1];
        this.drawString(context, verdict, x, y, counted[1] == 0 ? Ui.GREEN : Ui.GOLD);

        if (session.sourceStates().size() > 1) {
            this.drawString(context, "preview: most common state", x, y + 12, Ui.GREY);
        }
    }

    private void drawFooter(GuiContext context) {
        int y = Ui.MARGIN + (this.height - Ui.MARGIN * 2) - Ui.FOOTER_H + 3;
        this.drawString(context, "TYPE  filter", Ui.MARGIN + 6, y, Ui.GREY);
        this.drawString(context, "ENTER  pick top", Ui.MARGIN + 80, y, Ui.GREY);

        String hint = "ESC  back to the material list";
        this.drawString(context, hint,
                Ui.MARGIN + (this.width - Ui.MARGIN * 2) - 6 - this.getStringWidth(hint), y, Ui.GREY);
    }

    private Candidate firstRanked() {
        List<Candidate> current = this.getListWidget().getCurrentEntries();
        return current.isEmpty() ? null : current.get(0);
    }

    /** {@code oak_stairs[facing=north,half=bottom]}, trimmed to what the panel can hold. */
    static String describe(BlockState state) {
        String text = state.toString();
        int brace = text.indexOf('{');

        if (brace >= 0) {
            text = text.substring(brace + 1, text.length() - 1);
        }

        return text.replace("minecraft:", "").toLowerCase(Locale.ROOT);
    }

    private String trim(String text, int maxWidth) {
        String out = text;

        while (out.length() > 1 && this.getStringWidth(out) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }

        return out;
    }
}
