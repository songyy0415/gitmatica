package me.arnavpmr.lvc.gui.widgets;

import net.minecraft.client.input.MouseButtonEvent;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

public class WidgetLvcVerticalScrollbar extends WidgetBase
{
    private static final int TRACK_COLOR = 0xA0202020;
    private static final int THUMB_COLOR = 0xFFE0E0E0;
    private static final int MINIMUM_THUMB_HEIGHT = 14;

    private int contentHeight;
    private int viewportHeight;
    private int value;
    private boolean dragging;

    public WidgetLvcVerticalScrollbar(int x, int y, int width, int height)
    {
        super(x, y, width, height);
        this.viewportHeight = Math.max(0, height);
    }

    public void setRange(int contentHeight, int viewportHeight)
    {
        this.contentHeight = Math.max(0, contentHeight);
        this.viewportHeight = Math.max(0, viewportHeight);
        this.value = Math.clamp(this.value, 0, this.getMaxValue());
    }

    public int getValue()
    {
        return this.value;
    }

    public void setValue(int value)
    {
        this.value = Math.clamp(value, 0, this.getMaxValue());
    }

    public void offsetValue(int amount)
    {
        this.setValue(this.value + amount);
    }

    public void reset()
    {
        this.value = 0;
        this.dragging = false;
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY)
    {
        return this.isVisible() && super.isMouseOver(mouseX, mouseY);
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.input() != 0 || !this.isMouseOver((int) click.x(), (int) click.y()))
        {
            return false;
        }

        this.dragging = true;
        this.scrollToMouseY((int) click.y());
        return true;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        if (!this.dragging)
        {
            return false;
        }

        this.scrollToMouseY((int) click.y());
        return true;
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        if (click.input() == 0)
        {
            this.dragging = false;
        }
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (!this.isVisible())
        {
            return;
        }

        int thumbHeight = this.getThumbHeight();
        int thumbTravel = this.height - thumbHeight;
        int thumbY = this.y + thumbTravel * this.value / this.getMaxValue();

        RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, TRACK_COLOR);
        RenderUtils.drawRect(ctx, this.x, thumbY, this.width, thumbHeight, THUMB_COLOR);
    }

    private boolean isVisible()
    {
        return this.getMaxValue() > 0 && this.height > 0 && this.viewportHeight > 0;
    }

    private int getMaxValue()
    {
        return Math.max(0, this.contentHeight - this.viewportHeight);
    }

    private int getThumbHeight()
    {
        if (this.contentHeight <= 0)
        {
            return this.height;
        }

        return Math.clamp(this.height * this.viewportHeight / this.contentHeight,
                Math.min(MINIMUM_THUMB_HEIGHT, this.height), this.height);
    }

    private void scrollToMouseY(int mouseY)
    {
        int thumbHeight = this.getThumbHeight();
        int travel = this.height - thumbHeight;

        if (travel <= 0)
        {
            this.value = 0;
            return;
        }

        int relativeY = Math.clamp(mouseY - this.y - thumbHeight / 2, 0, travel);
        this.value = Math.round(relativeY * this.getMaxValue() / (float) travel);
    }
}
