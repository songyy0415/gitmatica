package me.niicide.lvc.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import me.niicide.lvc.gui.GitmaticaIcons;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcBranchDropdown extends WidgetLvcSearchableListDropdown<String>
{
    private static final int BRANCH_ICON_SIZE = 14;
    private static final String OPTIONS_TOOLTIP_KEY = "gitmatica.gui.label.lvc_project.branch_dropdown_options_hint";
    private static final String SOFT_LOAD_TOOLTIP_KEY = "gitmatica.gui.label.lvc_project.branch_dropdown_soft_load_hint";

    @Nullable private String headBranch;
    @Nullable private String detachedCommitId;
    private boolean detachedHead;

    public WidgetLvcBranchDropdown(int x, int y, int width, int height, int maxVisibleRows,
                                   List<String> branches, @Nullable String selectedBranch, @Nullable String headBranch,
                                   Consumer<String> selectionConsumer)
    {
        super(x, y, width, height, maxVisibleRows, branches, selectedBranch, selectionConsumer);
        this.setHeadBranch(headBranch);
    }

    public void setBranches(List<String> branches)
    {
        this.setItems(branches);
    }

    public static List<String> normalizeBranches(List<String> branches)
    {
        List<String> normalized = new ArrayList<>();

        for (String branch : branches)
        {
            if (branch != null && !branch.isBlank() && !normalized.contains(branch))
            {
                normalized.add(branch);
            }
        }

        return List.copyOf(normalized);
    }

    public void setSelectedBranch(@Nullable String selectedBranch)
    {
        this.setSelectedItem(selectedBranch);
    }

    public void setHeadBranch(@Nullable String headBranch)
    {
        this.headBranch = headBranch;
    }

    public void setDetachedHead(boolean detachedHead, @Nullable String detachedCommitId)
    {
        this.detachedHead = detachedHead;
        this.detachedCommitId = detachedCommitId;
    }

    @Override
    protected boolean isValidItem(@Nullable String item)
    {
        return item != null && !item.isBlank();
    }

    @Override
    protected String itemLabel(String item)
    {
        return item;
    }

    @Override
    protected String emptyListText()
    {
        return "No branches";
    }

    @Override
    protected String noSelectionText()
    {
        return "-";
    }

    @Override
    protected String closedLabel(@Nullable String selectedBranch)
    {
        if (this.detachedHead && this.detachedCommitId != null && !this.detachedCommitId.isBlank())
        {
            return this.detachedCommitId;
        }

        return super.closedLabel(selectedBranch);
    }

    @Override
    protected void renderClosedIcon(GuiContext ctx, int x, int y)
    {
        GitmaticaIcons icon = this.detachedHead ? GitmaticaIcons.DETACHED_HEAD : GitmaticaIcons.BRANCH;
        icon.renderScaledAt(ctx, x, y, BRANCH_ICON_SIZE, BRANCH_ICON_SIZE);
    }

    @Override
    protected void renderRowIcon(GuiContext ctx, String branch, int x, int y)
    {
        GitmaticaIcons icon = this.isHeadBranch(branch) ? GitmaticaIcons.CHECK : GitmaticaIcons.BRANCH;
        icon.renderScaledAt(ctx, x, y, BRANCH_ICON_SIZE, BRANCH_ICON_SIZE);
    }

    @Override
    protected List<String> closedTooltipLines()
    {
        return List.of(
                StringUtils.translate(OPTIONS_TOOLTIP_KEY),
                StringUtils.translate(SOFT_LOAD_TOOLTIP_KEY)
        );
    }

    @Override
    protected int iconSize()
    {
        return BRANCH_ICON_SIZE;
    }

    private boolean isHeadBranch(String branch)
    {
        return this.headBranch != null && this.headBranch.equals(branch);
    }
}
