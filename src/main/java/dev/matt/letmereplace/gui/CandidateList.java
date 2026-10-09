package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Candidate;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;

import java.util.Collection;
import java.util.List;

/**
 * The scrollable candidate list.
 *
 * <p>Ranking is ours, not malilib's: {@link #getAllEntries()} returns the already-ranked list for
 * the current query, so {@code entryMatchesFilter} passes everything through and sorting is
 * switched off. Doing it the other way round would let malilib's alphabetical sort undo ticket
 * 005 P4's tiers.
 */
public class CandidateList extends WidgetListBase<Candidate, WidgetCandidate> {

    private final PickerScreen screen;

    public CandidateList(int x, int y, int width, int height, PickerScreen screen) {
        super(x, y, width, height, screen::onSelectionChanged);
        this.screen = screen;
        this.browserEntryHeight = 18;
        this.browserEntriesOffsetY = 0;
        this.shouldSortList = false;
        this.allowKeyboardNavigation = true;
        // The icon is dereferenced in the constructor, so it must not be null.
        this.widgetSearchBar = new WidgetSearchBar(
                x + 2, Ui.MARGIN + Ui.TITLE_H + 3, width - 4, 14, 0,
                MaLiLibIcons.SEARCH, LeftRight.LEFT);
    }

    @Override
    protected Collection<Candidate> getAllEntries() {
        return screen.rankedFor(this.getFilterText());
    }

    @Override
    protected boolean entryMatchesFilter(Candidate entry, String filterText) {
        return true;                            // already filtered by the ranking above
    }

    @Override
    protected boolean getShouldSortList() {
        return false;                           // the ranking is the order
    }

    @Override
    protected List<String> getEntryStringsForFilter(Candidate entry) {
        return WidgetCandidate.filterStrings(entry);
    }

    @Override
    protected boolean onEntryClicked(Candidate entry, int index) {
        super.onEntryClicked(entry, index);
        screen.pick(entry);
        return true;
    }

    @Override
    protected WidgetCandidate createListEntryWidget(int x, int y, int listIndex,
                                                    boolean isOdd, Candidate entry) {
        return new WidgetCandidate(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, screen);
    }

    /** The entry a bare Enter should take, per ticket 005 P6. */
    public Candidate topEntry() {
        Candidate selected = this.getLastSelectedEntry();

        if (selected != null) {
            return selected;
        }

        List<Candidate> current = this.getCurrentEntries();
        return current.isEmpty() ? null : current.get(0);
    }
}
