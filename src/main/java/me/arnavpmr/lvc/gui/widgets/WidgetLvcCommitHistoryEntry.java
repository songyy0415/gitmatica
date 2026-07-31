package me.arnavpmr.lvc.gui.widgets;

import javax.annotation.Nullable;
import net.minecraft.client.input.MouseButtonEvent;

import me.arnavpmr.lvc.git.LvcCommitInfo;
import me.arnavpmr.lvc.gui.LvcGuiText;

import me.arnavpmr.lvc.gui.GitmaticaIcons;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcCommitHistoryEntry extends WidgetListEntryBase<LvcCommitInfo>
{
    private static final int TITLE_MAX_WIDTH = 132;
    private static final int AUTHOR_MAX_WIDTH = 72;
    private static final int UNDO_BUTTON_SIZE = 16;
    private static final int UNDO_ICON_SIZE = 12;
    private static final int HASH_SCROLLBAR_GUTTER = 9;
    private static final int UNDO_SCROLLBAR_INSET = 24;

    private final WidgetLvcCommitHistoryList list;
    private final boolean isOdd;
    @Nullable private final ButtonGeneric undoButton;

    public WidgetLvcCommitHistoryEntry(int x, int y, int width, int height, boolean isOdd,
                                       LvcCommitInfo entry, int listIndex,
                                       WidgetLvcCommitHistoryList list)
    {
        super(x, y, width, height, entry, listIndex);
        this.list = list;
        this.isOdd = isOdd;

        if (this.list.canUndo(entry))
        {
            int buttonX = x + width - UNDO_SCROLLBAR_INSET;
            ButtonGeneric button = new ButtonGeneric(buttonX, y + 1, UNDO_BUTTON_SIZE, UNDO_BUTTON_SIZE, "",
                    StringUtils.translate("gitmatica.gui.label.lvc_project.delete_latest_version"));
            this.undoButton = this.addButton(button, (ignored, ignoredMouseButton) -> this.list.undo(entry));
        }
        else
        {
            this.undoButton = null;
        }
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        return (this.undoButton == null || click.x() < this.undoButton.getX()) && super.canSelectAt(click);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        int color = this.isOdd ? 0xA0101010 : 0xA0303030;

        if (selected || this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0707070;
        }

        RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(ctx, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        this.drawCommitText(ctx);
        super.render(ctx, mouseX, mouseY, selected);

        if (this.undoButton != null)
        {
            int iconX = this.undoButton.getX() + (UNDO_BUTTON_SIZE - UNDO_ICON_SIZE) / 2;
            int iconY = this.undoButton.getY() + (UNDO_BUTTON_SIZE - UNDO_ICON_SIZE) / 2;
            GitmaticaIcons.UNDO.renderScaledAt(ctx, iconX, iconY, UNDO_ICON_SIZE, UNDO_ICON_SIZE);
        }
    }

    private void drawCommitText(GuiContext ctx)
    {
        int textX = this.x + 4;
        int textY = this.y + 5;
        int hashRightX = this.undoButton != null ?
                this.undoButton.getX() - 4 :
                this.x + this.width - HASH_SCROLLBAR_GUTTER;
        int hashWidth = this.getStringWidth(this.entry.shortId());
        int hashX = hashRightX - hashWidth;
        int availableTextWidth = Math.max(20, hashX - textX - 6);
        String author = " " + this.entry.author();
        int authorMaxWidth = Math.min(AUTHOR_MAX_WIDTH, availableTextWidth);
        String clippedAuthor = LvcGuiText.ellipsizeToWidth(author, authorMaxWidth, this::getStringWidth);
        int clippedAuthorWidth = this.getStringWidth(clippedAuthor);
        int titleMaxWidth = Math.min(TITLE_MAX_WIDTH, Math.max(0, availableTextWidth - clippedAuthorWidth));
        String clippedTitle = titleMaxWidth > 0 ?
                LvcGuiText.ellipsizeToWidth(this.entry.message(), titleMaxWidth, this::getStringWidth) : "";

        this.drawString(ctx, textX, textY, 0xFFFFFFFF, clippedTitle);
        this.drawString(ctx, textX + this.getStringWidth(clippedTitle), textY, 0xFF7A7A7A, clippedAuthor);
        this.drawString(ctx, hashX, textY, 0xFFFFD36A, this.entry.shortId());
    }
}
