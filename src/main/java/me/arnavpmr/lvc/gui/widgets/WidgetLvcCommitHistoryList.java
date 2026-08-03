package me.arnavpmr.lvc.gui.widgets;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import me.arnavpmr.lvc.git.LvcCommitInfo;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcCommitHistoryList extends WidgetListBase<LvcCommitInfo, WidgetLvcCommitHistoryEntry>
{
    private static final int ROW_HEIGHT = 18;
    private static final int SEARCH_HEIGHT = 18;
    private static final int SEARCH_OFFSET_Y = 23;
    private static final int SCROLLBAR_TRACK_RIGHT_OFFSET = 5;
    private static final int SCROLLBAR_TRACK_WIDTH = 4;

    private final Supplier<List<LvcCommitInfo>> historySupplier;
    private final Supplier<LvcCommitInfo> selectedCommitSupplier;
    private final Consumer<LvcCommitInfo> selectionConsumer;
    private final Predicate<LvcCommitInfo> undoPredicate;
    private final Consumer<LvcCommitInfo> undoConsumer;
    private final WidgetLvcCommitSearchBar searchBar;
    private final WidgetLvcVerticalScrollbar historyScrollbar;
    @Nullable private String pendingFocusedCommitId;

    public WidgetLvcCommitHistoryList(int x, int y, int width, int height,
                                      Supplier<List<LvcCommitInfo>> historySupplier,
                                      Supplier<LvcCommitInfo> selectedCommitSupplier,
                                      Consumer<LvcCommitInfo> selectionConsumer,
                                      Predicate<LvcCommitInfo> undoPredicate,
                                      Consumer<LvcCommitInfo> undoConsumer)
    {
        super(x, y, width, height, selectionConsumer::accept);
        this.historySupplier = historySupplier;
        this.selectedCommitSupplier = selectedCommitSupplier;
        this.selectionConsumer = selectionConsumer;
        this.undoPredicate = undoPredicate;
        this.undoConsumer = undoConsumer;
        this.browserEntryHeight = ROW_HEIGHT;
        this.searchBar = new WidgetLvcCommitSearchBar(
                x + 4, y + 4, Math.max(20, width - 8), SEARCH_HEIGHT);
        this.widgetSearchBar = this.searchBar;
        this.browserEntriesOffsetY = SEARCH_OFFSET_Y;
        this.historyScrollbar = new WidgetLvcVerticalScrollbar(
                x + width - SCROLLBAR_TRACK_RIGHT_OFFSET,
                y + 4 + SEARCH_OFFSET_Y,
                SCROLLBAR_TRACK_WIDTH,
                Math.max(0, height - SEARCH_OFFSET_Y - 4));
    }

    @Override
    public void setSize(int width, int height)
    {
        super.setSize(width, height);
        this.browserEntryWidth = Math.max(1, width - 4);
    }

    public void focusCommitAfterNextRefresh(String commitId)
    {
        this.pendingFocusedCommitId = commitId;
    }

    public void refreshHistory()
    {
        this.refreshEntries();

        String focusedCommitId = this.pendingFocusedCommitId;
        this.pendingFocusedCommitId = null;
        int index = this.indexOf(focusedCommitId);

        if (focusedCommitId != null && index < 0 && this.hasFilter())
        {
            this.searchBar.clearFilter();
            this.refreshEntries();
            index = this.indexOf(focusedCommitId);
        }

        if (index < 0)
        {
            LvcCommitInfo selectedCommit = this.selectedCommitSupplier.get();
            index = this.indexOf(selectedCommit != null ? selectedCommit.id() : null);
        }

        this.selectIndex(index >= 0 ? index : (this.listContents.isEmpty() ? -1 : 0));
    }

    public void blurSearch()
    {
        this.searchBar.blur();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        this.updateHistoryScrollbar();

        if (this.historyScrollbar.onMouseClicked(click, doubleClick))
        {
            this.syncListScrollFromHistoryScrollbar();
            return true;
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (this.historyScrollbar.onMouseDragged(click, dragXAmount, dragYAmount))
        {
            this.syncListScrollFromHistoryScrollbar();
            return true;
        }

        return super.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        this.historyScrollbar.onMouseReleased(click);
        return super.onMouseReleased(click);
    }

    boolean canUndo(LvcCommitInfo commit)
    {
        return this.undoPredicate.test(commit);
    }

    void undo(LvcCommitInfo commit)
    {
        this.undoConsumer.accept(commit);
    }

    @Override
    protected Collection<LvcCommitInfo> getAllEntries()
    {
        return this.historySupplier.get();
    }

    @Override
    protected boolean entryMatchesFilter(LvcCommitInfo entry, String filterText)
    {
        String[] tokens = filterText.trim().toLowerCase(Locale.ROOT).split("\\s+");
        String text = (entry.message() + "\n" + entry.description() + "\n" + entry.author()).toLowerCase(Locale.ROOT);
        String fullHash = entry.id().toLowerCase(Locale.ROOT);
        String shortHash = entry.shortId().toLowerCase(Locale.ROOT);

        for (String token : tokens)
        {
            if (!token.isEmpty() && !text.contains(token) &&
                    !shortHash.startsWith(token) && !fullHash.startsWith(token))
            {
                return false;
            }
        }

        return true;
    }

    @Override
    protected boolean onMouseClickedSearchBar(MouseButtonEvent click, boolean doubleClick)
    {
        boolean handled = super.onMouseClickedSearchBar(click, doubleClick);

        if (handled)
        {
            this.retainVisibleSelection();
        }

        return handled;
    }

    @Override
    protected boolean onKeyTypedSearchBar(KeyEvent input)
    {
        boolean handled = super.onKeyTypedSearchBar(input);

        if (handled)
        {
            this.retainVisibleSelection();
        }

        return handled;
    }

    @Override
    protected boolean onCharTypedSearchBar(CharacterEvent input)
    {
        boolean handled = super.onCharTypedSearchBar(input);

        if (handled)
        {
            this.retainVisibleSelection();
        }

        return handled;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        RenderUtils.drawOutlinedBox(ctx, this.posX, this.posY, this.browserWidth, this.browserHeight,
                0xB0000000, 0xFF999999);
        this.drawHistoryContents(ctx, mouseX, mouseY);

        if (this.listContents.isEmpty())
        {
            String key = this.historySupplier.get().isEmpty() ?
                    "gitmatica.gui.label.lvc_project.history_empty" :
                    "gitmatica.gui.label.lvc_project.history_no_matches";
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate(key),
                    this.posX + 6, this.posY + SEARCH_OFFSET_Y + 5, 0xFFAAAAAA, false);
        }
    }

    private void drawHistoryContents(GuiContext ctx, int mouseX, int mouseY)
    {
        WidgetBase hovered = null;

        for (WidgetLvcCommitHistoryEntry widget : this.listWidgets)
        {
            LvcCommitInfo entry = widget.getEntry();
            boolean selected = this.allowMultiSelection ?
                    this.selectedEntries.contains(entry) :
                    entry != null && entry.equals(this.getLastSelectedEntry());
            widget.render(ctx, mouseX, mouseY, selected);

            if (widget.isMouseOver(mouseX, mouseY))
            {
                hovered = widget;
            }
        }

        this.updateHistoryScrollbar();
        this.historyScrollbar.render(ctx, mouseX, mouseY, false);
        this.searchBar.render(ctx, mouseX, mouseY, false);

        if (hovered == null && this.searchBar.isMouseOver(mouseX, mouseY))
        {
            hovered = this.searchBar;
        }

        this.hoveredWidget = hovered;
    }

    private void updateHistoryScrollbar()
    {
        this.historyScrollbar.setRange(this.listContents.size(), this.maxVisibleBrowserEntries);
        this.historyScrollbar.setValue(this.scrollBar.getValue());
    }

    private void syncListScrollFromHistoryScrollbar()
    {
        int value = this.historyScrollbar.getValue();

        if (value != this.scrollBar.getValue())
        {
            this.scrollBar.setValue(value);
            this.lastScrollbarPosition = value;
            this.reCreateListEntryWidgets();
        }
    }

    @Override
    protected WidgetLvcCommitHistoryEntry createListEntryWidget(int x, int y, int listIndex,
                                                                 boolean isOdd, LvcCommitInfo entry)
    {
        return new WidgetLvcCommitHistoryEntry(x, y, this.browserEntryWidth,
                this.getBrowserEntryHeightFor(entry), isOdd, entry, listIndex, this);
    }

    private void retainVisibleSelection()
    {
        LvcCommitInfo selectedCommit = this.selectedCommitSupplier.get();
        int index = this.indexOf(selectedCommit != null ? selectedCommit.id() : null);
        this.selectIndex(index >= 0 ? index : (this.listContents.isEmpty() ? -1 : 0));
    }

    private int indexOf(@Nullable String commitId)
    {
        if (commitId == null)
        {
            return -1;
        }

        for (int index = 0; index < this.listContents.size(); index++)
        {
            if (this.listContents.get(index).id().equals(commitId))
            {
                return index;
            }
        }

        return -1;
    }

    private void selectIndex(int index)
    {
        if (index < 0 || index >= this.listContents.size())
        {
            this.clearSelection();
            this.selectionConsumer.accept(null);
            this.scrollBar.setValue(0);
            this.reCreateListEntryWidgets();
            return;
        }

        if (index < this.scrollBar.getValue())
        {
            this.scrollBar.setValue(index);
        }
        else if (index >= this.scrollBar.getValue() + this.maxVisibleBrowserEntries)
        {
            this.scrollBar.setValue(Math.max(0, index - this.maxVisibleBrowserEntries + 1));
        }

        this.setLastSelectedEntry(this.listContents.get(index), index);
        this.reCreateListEntryWidgets();
    }

    private static class WidgetLvcCommitSearchBar extends WidgetSearchBar
    {
        private WidgetLvcCommitSearchBar(int x, int y, int width, int height)
        {
            super(x, y, width, height, 0, Icons.FILE_ICON_SEARCH, LeftRight.LEFT);
            this.iconSearch.setPosition(x + 4, y + 3);
            this.searchBox.setY(y + (height - this.fontHeight) / 2);
            this.searchBox.setBordered(false);
            this.searchBox.setMaxLength(128);
            this.searchBox.setTextColor(0xFFFFFFFF);
            this.searchBox.setTextColorUneditable(0xFFAAAAAA);
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
        {
            this.guiContext = ctx;
            RenderUtils.drawOutlinedBox(ctx, this.x, this.y, this.width, this.height,
                    0xA0000000, 0xFF999999);
            this.iconSearch.render(ctx, true, false);

            if (this.searchOpen)
            {
                this.searchBox.extractRenderState(ctx.getGuiGraphics(), mouseX, mouseY, 0);
            }
        }

        private void clearFilter()
        {
            this.searchBox.setValue("");
        }

        private void blur()
        {
            this.searchBox.setFocused(false);
        }
    }
}
