package me.arnavpmr.lvc.gui.widgets;

import javax.annotation.Nullable;

import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Group;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.arnavpmr.lvc.diff.LvcVerifierDiffGroups.Entry;

import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryOverlay;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryPreview;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetListLvcChangeViewerEntries
        extends WidgetListBase<LvcChangeEntry, WidgetLvcChangeViewerEntry>
{
    private static int lastScrollbarPosition;

    private final LvcChangeViewerView view;
    private boolean scrollbarRestored;

    public WidgetListLvcChangeViewerEntries(int x, int y, int width, int height,
                                            LvcChangeViewerView view,
                                            ISelectionListener<LvcChangeEntry> selectionListener)
    {
        super(x, y, width, height, selectionListener);
        this.browserEntryHeight = 22;
        this.view = view;
        this.allowMultiSelection = true;
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        VerifierInventoryPreview preview = this.view.lockedInventoryPreview();

        if (preview == null)
        {
            preview = this.hoveredInventoryPreview(mouseX, mouseY);
        }

        if (preview != null)
        {
            VerifierInventoryOverlay.renderPreviewTooltip(ctx, preview, mouseX, mouseY,
                    "gitmatica.gui.label.lvc_change_viewer.before",
                    "gitmatica.gui.label.lvc_change_viewer.after");
        }

        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected boolean shouldRenderHoverStuff()
    {
        return this.view.lockedInventoryPreview() == null;
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection)
    {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected WidgetLvcChangeViewerEntry createHeaderWidget(int x, int y, int listIndexStart,
                                                             int usableHeight, int usedHeight)
    {
        int height = this.browserEntryHeight;

        if (usedHeight + height > usableHeight)
        {
            return null;
        }

        return this.createListEntryWidget(x, y, listIndexStart, true, LvcChangeEntry.header());
    }

    @Override
    protected void refreshBrowserEntries()
    {
        this.listContents.clear();

        int groupNumber = 1;
        boolean hasVisibleGroups = false;

        for (Group<Entry> group : this.view.spatialGroups())
        {
            if (this.view.isGroupVisible(group))
            {
                this.addGroup(group, groupNumber);
                hasVisibleGroups = true;
            }

            groupNumber++;
        }

        if (!hasVisibleGroups)
        {
            this.listContents.add(LvcChangeEntry.empty(
                    StringUtils.translate("gitmatica.gui.label.lvc_change_viewer.empty_category")));
        }

        this.reCreateListEntryWidgets();

        if (!this.scrollbarRestored && lastScrollbarPosition <= this.scrollBar.getMaxValue())
        {
            this.scrollBar.setValue(lastScrollbarPosition);
            this.scrollbarRestored = true;
            this.reCreateListEntryWidgets();
        }
    }

    @Override
    protected WidgetLvcChangeViewerEntry createListEntryWidget(int x, int y, int listIndex,
                                                                boolean isOdd, LvcChangeEntry entry)
    {
        return new WidgetLvcChangeViewerEntry(x, y, this.browserEntryWidth,
                this.getBrowserEntryHeightFor(entry), isOdd, this, this.view, entry, listIndex);
    }

    private void addGroup(Group<Entry> group, int groupNumber)
    {
        this.listContents.add(LvcChangeEntry.group(group, groupNumber));

        if (!this.view.isGroupExpanded(group.anchor()))
        {
            return;
        }

        for (Kind kind : Kind.values())
        {
            if (!this.view.isKindVisible(kind))
            {
                continue;
            }

            java.util.List<Entry> entries = group.entries(kind);

            if (entries.isEmpty())
            {
                continue;
            }

            this.listContents.add(LvcChangeEntry.kind(group, groupNumber, kind));

            if (this.view.isKindExpanded(group.anchor(), kind))
            {
                for (Entry entry : entries)
                {
                    this.listContents.add(LvcChangeEntry.data(group, groupNumber, kind, entry));
                }
            }
        }
    }

    @Nullable
    private VerifierInventoryPreview hoveredInventoryPreview(int mouseX, int mouseY)
    {
        for (WidgetLvcChangeViewerEntry widget : this.listWidgets)
        {
            VerifierInventoryPreview preview = widget.hoveredInventoryPreview(mouseX, mouseY);

            if (preview != null)
            {
                return preview;
            }
        }

        return null;
    }
}
