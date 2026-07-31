package me.arnavpmr.lvc.gui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;

import me.arnavpmr.lvc.git.LvcCommitInfo;
import me.arnavpmr.lvc.gui.LvcGuiText;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcCommitMetadataPanel extends WidgetBase
{
    private static final int SCROLL_STEP = 12;
    private static final int TEXT_PADDING = 6;
    private static final int SCROLLBAR_GUTTER_WIDTH = 16;
    private static final int SCROLLBAR_TRACK_RIGHT_OFFSET = 9;
    private static final int SCROLLBAR_TRACK_WIDTH = 4;
    private static final int TEXT_COLOR = 0xFFB0B0B0;
    private static final int VALUE_COLOR = 0xFFFFFFFF;

    private final Supplier<LvcCommitInfo> selectedCommitSupplier;
    private final WidgetLvcVerticalScrollbar scrollbar;
    private int contentHeight;

    public WidgetLvcCommitMetadataPanel(int x, int y, int width, int height,
                                        Supplier<LvcCommitInfo> selectedCommitSupplier)
    {
        super(x, y, width, height);
        this.selectedCommitSupplier = selectedCommitSupplier;
        this.scrollbar = new WidgetLvcVerticalScrollbar(
                x + width - SCROLLBAR_TRACK_RIGHT_OFFSET,
                y + TEXT_PADDING,
                SCROLLBAR_TRACK_WIDTH,
                Math.max(0, height - TEXT_PADDING * 2));
    }

    public void resetScroll()
    {
        this.scrollbar.reset();
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        return this.scrollbar.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        return this.scrollbar.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        this.scrollbar.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolledImpl(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (!this.isMouseOver((int) mouseX, (int) mouseY) || verticalAmount == 0)
        {
            return false;
        }

        int previous = this.scrollbar.getValue();
        this.scrollbar.offsetValue((int) (-verticalAmount * SCROLL_STEP));
        return previous != this.scrollbar.getValue();
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.width <= 0 || this.height <= 0)
        {
            return;
        }

        RenderUtils.drawOutlinedBox(ctx, this.x, this.y, this.width, this.height, 0xA0000000, 0xFF999999);

        LvcCommitInfo selectedCommit = this.selectedCommitSupplier.get();
        int textX = this.x + TEXT_PADDING;
        int textY = this.y + TEXT_PADDING;

        if (selectedCommit == null)
        {
            this.contentHeight = 0;
            this.scrollbar.setRange(0, this.getViewportHeight());
            this.drawString(ctx, textX, textY, 0xFFAAAAAA,
                    StringUtils.translate("gitmatica.gui.label.lvc_project.no_commit_selected"));
            return;
        }

        int textWidth = this.getTextWidth();
        List<CommitMetadataLine> lines = this.createLines(selectedCommit, textWidth);
        this.contentHeight = this.getContentHeight(lines.size());
        this.scrollbar.setRange(this.contentHeight, this.getViewportHeight());

        ctx.pushScissor(new ScreenRectangle(textX, textY, textWidth + 1, this.getViewportHeight()));

        int drawY = textY - this.scrollbar.getValue();

        for (CommitMetadataLine line : lines)
        {
            int lineX = textX + line.indent();
            this.drawString(ctx, lineX, drawY, TEXT_COLOR, line.label());

            if (line.value() != null)
            {
                this.drawString(ctx, lineX + line.valueOffset(), drawY, VALUE_COLOR, line.value());
            }

            drawY += SCROLL_STEP;
        }

        ctx.popScissor();
        this.scrollbar.render(ctx, mouseX, mouseY, false);
    }

    private List<CommitMetadataLine> createLines(LvcCommitInfo commit, int maxWidth)
    {
        List<CommitMetadataLine> lines = new ArrayList<>();
        this.addWrappedInlineLine(lines, "gitmatica.gui.label.lvc_project.info_title", commit.message(), maxWidth);
        this.addInlineLine(lines, "gitmatica.gui.label.lvc_project.info_author", commit.author(), maxWidth);
        this.addInlineLine(lines, "gitmatica.gui.label.lvc_project.info_date", commit.time(), maxWidth);
        this.addInlineLine(lines, "gitmatica.gui.label.lvc_project.info_version", commit.shortId(), maxWidth);

        if (commit.description() != null && !commit.description().isBlank())
        {
            this.addBlockLines(lines, "gitmatica.gui.label.lvc_project.info_description",
                    commit.description(), maxWidth);
        }

        String changes = commit.changes();
        this.addBlockLines(lines, "gitmatica.gui.label.lvc_project.info_changes",
                changes == null || changes.isBlank() ?
                        StringUtils.translate("gitmatica.gui.label.lvc_project.changes_unavailable") : changes,
                maxWidth);
        return lines;
    }

    private void addInlineLine(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        String label = StringUtils.translate(labelKey) + ": ";
        int labelWidth = this.getStringWidth(label);
        int valueWidth = Math.max(20, maxWidth - labelWidth);
        lines.add(new CommitMetadataLine(label,
                LvcGuiText.ellipsizeToWidth(value, valueWidth, this::getStringWidth), 0, labelWidth));
    }

    private void addWrappedInlineLine(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        String label = StringUtils.translate(labelKey) + ": ";
        int labelWidth = this.getStringWidth(label);
        int valueWidth = Math.max(20, maxWidth - labelWidth);
        List<String> wrappedLines = LvcGuiText.wrapTextToWidth(value, valueWidth, this::getStringWidth);

        lines.add(new CommitMetadataLine(label, wrappedLines.get(0), 0, labelWidth));

        for (int index = 1; index < wrappedLines.size(); index++)
        {
            lines.add(new CommitMetadataLine("", wrappedLines.get(index), labelWidth, 0));
        }
    }

    private void addBlockLines(List<CommitMetadataLine> lines, String labelKey, String value, int maxWidth)
    {
        int indent = 12;
        lines.add(new CommitMetadataLine(StringUtils.translate(labelKey) + ":", null, 0, 0));

        for (String line : LvcGuiText.wrapTextToWidth(value,
                Math.max(20, maxWidth - indent), this::getStringWidth))
        {
            lines.add(new CommitMetadataLine("", line, indent, 0));
        }
    }

    private int getTextWidth()
    {
        return Math.max(20, this.width - TEXT_PADDING - SCROLLBAR_GUTTER_WIDTH);
    }

    private int getViewportHeight()
    {
        return Math.max(0, this.height - TEXT_PADDING * 2);
    }

    private int getContentHeight(int lineCount)
    {
        return lineCount <= 0 ? 0 : (lineCount - 1) * SCROLL_STEP + Math.max(0, this.fontHeight - 1);
    }

    private record CommitMetadataLine(String label, @Nullable String value, int indent, int valueOffset)
    {
    }
}
