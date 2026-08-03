package me.arnavpmr.lvc.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import javax.annotation.Nullable;

import me.arnavpmr.lvc.gui.widgets.WidgetLvcPopupHost;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcSearchableListDropdown;

import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

abstract class GuiLvcPanelDialog extends GuiDialogBase
{
    protected static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;
    protected static final int PANEL_BACKGROUND_COLOR = 0xE0000000;
    protected final int panelPadding;
    @Nullable private WidgetLvcPopupHost popupHost;

    protected GuiLvcPanelDialog(Screen parent, String titleKey, int width, int height, int padding)
    {
        this.panelPadding = padding;
        this.setParent(parent);
        this.title = StringUtils.translate(titleKey);
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(width, height);
    }

    @Override
    protected final void drawWidgets(GuiContext ctx, int mouseX, int mouseY)
    {
    }

    @Override
    protected final void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected final void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        // Panel dialogs render their title inside the outlined popup.
    }

    @Override
    protected final void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        Screen parent = this.getParent();

        if (parent != null)
        {
            parent.extractRenderState(ctx.getGuiGraphics(), BACKGROUND_MOUSE, BACKGROUND_MOUSE, partialTicks);
        }

        RenderUtils.drawOutlinedBox(
                ctx,
                this.dialogLeft,
                this.dialogTop,
                this.dialogWidth,
                this.dialogHeight,
                PANEL_BACKGROUND_COLOR,
                COLOR_HORIZONTAL_BAR
        );
        this.drawStringWithShadow(
                ctx,
                this.getTitleString(),
                this.dialogLeft + this.panelPadding,
                this.dialogTop + this.titleOffsetY(),
                COLOR_WHITE
        );
        this.drawPanelContents(ctx, mouseX, mouseY, partialTicks);
        super.drawWidgets(ctx, mouseX, mouseY);
        this.drawPanelWidgets(ctx, mouseX, mouseY, partialTicks);
        super.drawButtons(
                ctx,
                this.buttonMouseX(mouseX),
                this.buttonMouseY(mouseY),
                partialTicks
        );
        this.drawPanelForeground(ctx, mouseX, mouseY, partialTicks);

        if (this.popupHost != null)
        {
            this.popupHost.renderPopup(ctx, mouseX, mouseY);
        }
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return this.handlePopupClick(click, doubleClick) || super.onMouseClicked(click, doubleClick);
    }

    protected int titleOffsetY()
    {
        return 6;
    }

    protected int buttonMouseX(int mouseX)
    {
        return this.isPopupOpen() ? BACKGROUND_MOUSE : mouseX;
    }

    protected int buttonMouseY(int mouseY)
    {
        return this.isPopupOpen() ? BACKGROUND_MOUSE : mouseY;
    }

    protected final <T extends WidgetLvcSearchableListDropdown<?>> T addPopup(T popup)
    {
        this.popupHost = this.addWidget(new WidgetLvcPopupHost(popup));
        return popup;
    }

    protected final boolean handlePopupClick(MouseButtonEvent click, boolean doubleClick)
    {
        return this.popupHost != null && this.popupHost.handleModalClick(click, doubleClick);
    }

    protected final boolean isPopupOpen()
    {
        return this.popupHost != null && this.popupHost.isOpen();
    }

    protected void drawPanelContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    protected void drawPanelWidgets(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    protected void drawPanelForeground(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }
}
