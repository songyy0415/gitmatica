package me.niicide.lvc.gui.widgets;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.game.BlockUtils;

final class WidgetLvcBlockStateDiffTooltip extends WidgetBase
{
    private static final String HEADER_BEFORE = "gitmatica.gui.label.lvc_change_viewer.before";
    private static final String HEADER_AFTER = "gitmatica.gui.label.lvc_change_viewer.after";

    final BlockState stateBefore;
    final BlockState stateAfter;
    final ItemStack stackBefore;
    final ItemStack stackAfter;
    final String nameBefore;
    final String nameAfter;

    private final String registryBefore;
    private final String registryAfter;
    private final int columnWidthBefore;

    WidgetLvcBlockStateDiffTooltip(BlockState stateBefore, BlockState stateAfter)
    {
        this(stateBefore, stateAfter, createContent(stateBefore, stateAfter));
    }

    private WidgetLvcBlockStateDiffTooltip(BlockState stateBefore, BlockState stateAfter,
                                           TooltipContent content)
    {
        super(0, 0, content.totalWidth(), totalHeight(stateBefore, stateAfter));
        this.stateBefore = stateBefore;
        this.stateAfter = stateAfter;
        this.stackBefore = content.stackBefore();
        this.stackAfter = content.stackAfter();
        this.registryBefore = content.registryBefore();
        this.registryAfter = content.registryAfter();
        this.nameBefore = content.nameBefore();
        this.nameAfter = content.nameAfter();
        this.columnWidthBefore = content.columnWidthBefore();
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        super.render(ctx, mouseX, mouseY, selected);
        fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(
                ctx, this.x + 1, this.y + 1, this.width - 1, this.height - 1);
        RenderUtils.drawOutlinedBox(
                ctx, this.x, this.y, this.width, this.height, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
        int x1 = this.x + 10;
        int x2 = this.x + this.columnWidthBefore + 30;
        int y = this.y + 4;
        String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
        ctx.drawString(ctx.fontRenderer(),
                pre + StringUtils.translate(HEADER_BEFORE) + GuiBase.TXT_RST,
                x1, y, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(),
                pre + StringUtils.translate(HEADER_AFTER) + GuiBase.TXT_RST,
                x2, y, 0xFFFFFFFF, false);
        y += 12;
        RenderUtils.drawRect(ctx, x1, y, 16, 16, 0x50C0C0C0);
        RenderUtils.drawRect(ctx, x2, y, 16, 16, 0x50C0C0C0);
        int iconY = y;
        ctx.drawString(ctx.fontRenderer(), this.nameBefore, x1 + 20, y + 4, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(), this.nameAfter, x2 + 20, y + 4, 0xFFFFFFFF, false);
        y += 20;
        ctx.drawString(ctx.fontRenderer(), this.registryBefore, x1, y, 0xFF4060FF, false);
        ctx.drawString(ctx.fontRenderer(), this.registryAfter, x2, y, 0xFF4060FF, false);
        y += StringUtils.getFontHeight() + 4;
        RenderUtils.renderText(ctx, x1, y, 0xFFB0B0B0,
                BlockUtils.getFormattedBlockStateProperties(this.stateBefore, " = "));
        RenderUtils.renderText(ctx, x2, y, 0xFFB0B0B0,
                BlockUtils.getFormattedBlockStateProperties(this.stateAfter, " = "));
        renderHoverIcon(ctx, x1, iconY, this.stateBefore, this.stackBefore);
        renderHoverIcon(ctx, x2, iconY, this.stateAfter, this.stackAfter);
    }

    private static String registryName(BlockState state)
    {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null ? id.toString() : "<null>";
    }

    private static int columnWidth(BlockState state, String registryName, String displayName)
    {
        int width = Math.max(StringUtils.getStringWidth(displayName) + 20,
                StringUtils.getStringWidth(registryName));
        return Math.max(width, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(
                BlockUtils.getFormattedBlockStateProperties(state, " = ")));
    }

    private static int totalHeight(BlockState before, BlockState after)
    {
        List<String> propsBefore = BlockUtils.getFormattedBlockStateProperties(before, " = ");
        List<String> propsAfter = BlockUtils.getFormattedBlockStateProperties(after, " = ");
        return Math.max(propsBefore.size(), propsAfter.size()) * (StringUtils.getFontHeight() + 2) + 60;
    }

    private static TooltipContent createContent(BlockState before, BlockState after)
    {
        ItemStack stackBefore = ItemUtils.getItemForState(before);
        ItemStack stackAfter = ItemUtils.getItemForState(after);
        String registryBefore = registryName(before);
        String registryAfter = registryName(after);
        String nameBefore = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(before, stackBefore);
        String nameAfter = WidgetSchematicVerificationResult.BlockMismatchInfo.getDisplayName(after, stackAfter);
        int columnWidthBefore = columnWidth(before, registryBefore, nameBefore);
        int totalWidth = columnWidthBefore + columnWidth(after, registryAfter, nameAfter) + 40;
        return new TooltipContent(stackBefore, stackAfter, registryBefore, registryAfter,
                nameBefore, nameAfter, columnWidthBefore, totalWidth);
    }

    private static void renderHoverIcon(GuiContext ctx, int x, int y, BlockState state, ItemStack stack)
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
            ctx.renderItemDecorations(ctx.fontRenderer(), stack, x, y);
        }
    }

    private record TooltipContent(ItemStack stackBefore, ItemStack stackAfter,
                                  String registryBefore, String registryAfter,
                                  String nameBefore, String nameAfter,
                                  int columnWidthBefore, int totalWidth)
    {
    }
}
