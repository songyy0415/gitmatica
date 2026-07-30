package me.niicide.lvc.gui.widgets;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import me.niicide.lvc.diff.LvcSpatialDiffGroups.Kind;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import me.niicide.lvc.gui.GitmaticaIcons;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.SortCriteria;
import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryPreview;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcChangeViewerEntry extends WidgetListEntrySortable<LvcChangeEntry>
{
    private static final int DATA_INDENT = 44;
    private static final int GROUP_INDENT = 4;
    private static final int KIND_INDENT = 20;
    private static final int DISCLOSURE_ICON_WIDTH = Math.max(
            Icons.ARROW_DOWN.getWidth(), GitmaticaIcons.ARROW_RIGHT.getWidth());
    private static final int DISCLOSURE_CLICK_PADDING = 4;
    private static final int DISCLOSURE_TEXT_GAP = 4;
    private static final int HEADER_OUTLINE_INSET = 3;
    private static final int STATE_COLUMN_PADDING = 40;
    private static final String HEADER_BEFORE = "gitmatica.gui.label.lvc_change_viewer.before";
    private static final String HEADER_AFTER = "gitmatica.gui.label.lvc_change_viewer.after";
    private static final String HEADER_COUNT = "litematica.gui.label.schematic_verifier.count()";

    private static int maxNameLengthBefore;
    private static int maxNameLengthAfter;
    private static int maxCountLength;

    private final WidgetListLvcChangeViewerEntries listWidget;
    private final LvcChangeViewerView view;
    private final SchematicVerifier verifier;
    private final LvcChangeEntry entry;
    private final boolean isOdd;
    @Nullable private final WidgetLvcBlockStateDiffTooltip mismatchTooltip;
    @Nullable private final ButtonGeneric buttonHide;

    public WidgetLvcChangeViewerEntry(int x, int y, int width, int height, boolean isOdd,
                                      WidgetListLvcChangeViewerEntries listWidget,
                                      LvcChangeViewerView view, LvcChangeEntry entry, int listIndex)
    {
        super(x, y, width, height, entry, listIndex);
        this.columnCount = 3;
        this.listWidget = listWidget;
        this.view = view;
        this.verifier = view.verifier();
        this.entry = entry;
        this.isOdd = isOdd;

        if (entry.type() == LvcChangeEntry.Type.DATA && entry.mismatch() != null)
        {
            this.mismatchTooltip = new WidgetLvcBlockStateDiffTooltip(
                    entry.mismatch().stateExpected(), entry.mismatch().stateFound());
            this.buttonHide = this.createButton(this.x + this.width, y + 1);
        }
        else
        {
            this.mismatchTooltip = null;
            this.buttonHide = null;
        }
    }

    public static void setMaxNameLengths(List<BlockMismatch> mismatches)
    {
        maxNameLengthBefore = StringUtils.getStringWidth(
                GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
        maxNameLengthAfter = StringUtils.getStringWidth(
                GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
        maxCountLength = 7 * StringUtils.getStringWidth("8");

        for (BlockMismatch mismatch : mismatches)
        {
            ItemStack stack = ItemUtils.getItemForState(mismatch.stateExpected());
            String name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(
                    mismatch.stateExpected(), stack);
            maxNameLengthBefore = Math.max(maxNameLengthBefore, StringUtils.getStringWidth(name));
            stack = ItemUtils.getItemForState(mismatch.stateFound());
            name = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(
                    mismatch.stateFound(), stack);
            maxNameLengthAfter = Math.max(maxNameLengthAfter, StringUtils.getStringWidth(name));
        }

        maxCountLength = Math.max(maxCountLength, StringUtils.getStringWidth(
                GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST));
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.verifier.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.verifier.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        if (this.entry.type() == LvcChangeEntry.Type.HEADER && column == 0)
        {
            return this.x + HEADER_OUTLINE_INSET;
        }

        int x1 = this.x + DATA_INDENT;
        int x2 = x1 + maxNameLengthBefore + STATE_COLUMN_PADDING;
        int x3 = x2 + maxNameLengthAfter + STATE_COLUMN_PADDING;

        return switch (column)
        {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x3 + maxCountLength + 20;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.input() == 0 && this.isOverExpansionArrow(click))
        {
            this.view.toggleExpanded(this.entry);
            return true;
        }

        if (super.onMouseClickedImpl(click, doubleClick))
        {
            return true;
        }

        if (this.entry.type() != LvcChangeEntry.Type.HEADER)
        {
            return false;
        }

        int column = this.getMouseOverColumn((int) click.x(), (int) click.y());

        switch (column)
        {
            case 0 -> this.verifier.setSortCriteria(SortCriteria.NAME_EXPECTED);
            case 1 -> this.verifier.setSortCriteria(SortCriteria.NAME_FOUND);
            case 2 -> this.verifier.setSortCriteria(SortCriteria.COUNT);
            default ->
            {
                return false;
            }
        }

        this.listWidget.refreshEntries();
        return true;
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        if (click.input() != 0 ||
                this.entry.type() == LvcChangeEntry.Type.HEADER ||
                this.entry.type() == LvcChangeEntry.Type.EMPTY ||
                this.isOverExpansionArrow(click))
        {
            return false;
        }

        return (this.buttonHide == null || click.x() < this.buttonHide.getX()) && super.canSelectAt(click);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        int color = this.isOdd ? 0xA0101010 : 0xA0303030;

        if (selected)
        {
            color = 0xA0707070;
        }
        else if (this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0505050;
        }

        RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(ctx, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int y = this.y + 7;

        switch (this.entry.type())
        {
            case HEADER ->
            {
                this.drawString(ctx, x1, y, 0xFFFFFFFF,
                        GuiBase.TXT_BOLD + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST);
                this.drawString(ctx, x2, y, 0xFFFFFFFF,
                        GuiBase.TXT_BOLD + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST);
                this.drawString(ctx, x3, y, 0xFFFFFFFF,
                        GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST);
                this.renderColumnHeader(ctx, mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
            }
            case GROUP -> this.renderExpandableRow(ctx, GROUP_INDENT, 0xFFFFFFFF, false, x3);
            case KIND -> this.renderExpandableRow(ctx, KIND_INDENT,
                    this.entry.kind() != null ? LvcChangeEntry.kindTextColor(this.entry.kind()) : 0xFFFFFFFF,
                    true, x3);
            case EMPTY -> this.drawString(ctx, this.x + DATA_INDENT + 18, y, 0xFFB0B0B0,
                    Objects.toString(this.entry.label(), ""));
            case DATA -> this.renderDataRow(ctx, x1, x2, x3);
        }

        super.render(ctx, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.canShowInventoryPreview() && (this.buttonHide == null || mouseX < this.buttonHide.getX()))
        {
            return;
        }

        if (this.mismatchTooltip != null && this.buttonHide != null && mouseX < this.buttonHide.getX())
        {
            int x = mouseX + 10;
            int y = mouseY;
            int width = this.mismatchTooltip.getWidth();
            int height = this.mismatchTooltip.getHeight();

            if (x + width > GuiUtils.getCurrentScreenWidth())
            {
                x = mouseX - width - 10;
            }

            if (y + height > GuiUtils.getCurrentScreenHeight())
            {
                y = mouseY - height - 2;
            }

            this.mismatchTooltip.setPosition(x, y);
            this.mismatchTooltip.render(ctx, mouseX, mouseY, false);
        }
    }

    @Nullable
    VerifierInventoryPreview hoveredInventoryPreview(int mouseX, int mouseY)
    {
        if (!this.isMouseOver(mouseX, mouseY) ||
                (this.buttonHide != null && mouseX >= this.buttonHide.getX()))
        {
            return null;
        }

        return this.entry.inventoryPreview();
    }

    private ButtonGeneric createButton(int x, int y)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true,
                StringUtils.translate("gitmatica.gui.button.lvc_change_viewer.hide"));
        return this.addButton(button, new HideButtonListener(this.entry, this.view));
    }

    private boolean isExpanded()
    {
        if (this.entry.type() == LvcChangeEntry.Type.GROUP && this.entry.groupAnchor() != null)
        {
            return this.view.isGroupExpanded(this.entry.groupAnchor());
        }

        return this.entry.type() == LvcChangeEntry.Type.KIND &&
                this.entry.groupAnchor() != null && this.entry.kind() != null &&
                this.view.isKindExpanded(this.entry.groupAnchor(), this.entry.kind());
    }

    private void renderExpandableRow(GuiContext ctx, int indent, int color, boolean showCount, int countX)
    {
        IGuiIcon icon = this.isExpanded() ? Icons.ARROW_DOWN : GitmaticaIcons.ARROW_RIGHT;
        int iconX = this.x + indent;
        int iconY = this.y + (this.height - icon.getHeight()) / 2;
        icon.renderAt(ctx, iconX, iconY, 0, true, false);
        this.drawString(ctx, this.x + indent + DISCLOSURE_ICON_WIDTH + DISCLOSURE_TEXT_GAP,
                this.y + 7, color, Objects.toString(this.entry.label(), ""));

        if (showCount)
        {
            this.drawString(ctx, countX, this.y + 7, 0xFFFFFFFF, String.valueOf(this.entry.count()));
        }
    }

    private boolean isOverExpansionArrow(MouseButtonEvent click)
    {
        int indent = switch (this.entry.type())
        {
            case GROUP -> GROUP_INDENT;
            case KIND -> KIND_INDENT;
            default -> -1;
        };

        if (indent < 0)
        {
            return false;
        }

        int iconX = this.x + indent;
        return click.x() >= iconX - DISCLOSURE_CLICK_PADDING &&
                click.x() < iconX + DISCLOSURE_ICON_WIDTH + DISCLOSURE_CLICK_PADDING &&
                click.y() >= this.y && click.y() < this.y + this.height;
    }

    private void renderDataRow(GuiContext ctx, int x1, int x2, int x3)
    {
        if (this.mismatchTooltip == null || this.entry.mismatch() == null)
        {
            return;
        }

        int y = this.y + 7;
        this.drawString(ctx, x1 + 20, y, 0xFFFFFFFF, this.mismatchTooltip.nameBefore);
        this.drawString(ctx, x2 + 20, y, 0xFFFFFFFF, this.mismatchTooltip.nameAfter);
        this.drawString(ctx, x3, y, 0xFFFFFFFF, String.valueOf(this.entry.mismatch().count()));

        y = this.y + 3;
        RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x20FFFFFF);
        RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x20FFFFFF);
        this.renderStateIcon(ctx, x1, y, this.mismatchTooltip.stateBefore, this.mismatchTooltip.stackBefore);
        this.renderStateIcon(ctx, x2, y, this.mismatchTooltip.stateAfter, this.mismatchTooltip.stackAfter);
    }

    private void renderStateIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
    {
        boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
        boolean isAirItem = stack.isEmpty();
        boolean useBlockModel = hasModel && (isAirItem || state.getBlock() == Blocks.FLOWER_POT);

        if (useBlockModel && fi.dy.masa.litematica.render.RenderUtils.stateModelHasQuads(state))
        {
            WidgetSchematicVerificationResult.renderModelInGui(ctx, x, y, 1, state);
        }
        else
        {
            ctx.renderItem(stack, x, y);
            ctx.renderItemDecorations(this.textRenderer, stack, x, y);
        }
    }

    private boolean canShowInventoryPreview()
    {
        return this.entry.inventoryPreview() != null;
    }

    private record HideButtonListener(LvcChangeEntry entry,
                                      LvcChangeViewerView view) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.entry.mismatch() != null)
            {
                this.view.hideMismatch(this.entry.mismatch());
            }
        }
    }
}
