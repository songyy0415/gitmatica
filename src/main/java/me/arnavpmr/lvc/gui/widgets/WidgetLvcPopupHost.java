package me.arnavpmr.lvc.gui.widgets;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;

public class WidgetLvcPopupHost extends WidgetBase
{
    private final WidgetLvcSearchableListDropdown<?> dropdown;

    public WidgetLvcPopupHost(WidgetLvcSearchableListDropdown<?> dropdown)
    {
        super(dropdown.getX(), dropdown.getY(), dropdown.getWidth(), dropdown.getHeight());
        this.dropdown = dropdown;
    }

    public boolean isOpen()
    {
        return this.dropdown.isOpen();
    }

    public void close()
    {
        this.dropdown.close();
    }

    public boolean handleModalClick(MouseButtonEvent click, boolean doubleClick)
    {
        if (!this.isOpen())
        {
            return false;
        }

        if (this.dropdown.isMouseOver((int) click.x(), (int) click.y()))
        {
            return this.dropdown.onMouseClicked(click, doubleClick);
        }

        this.close();
        return true;
    }

    public void renderPopup(GuiContext ctx, int mouseX, int mouseY)
    {
        this.dropdown.renderPopup(ctx, mouseX, mouseY);
    }

    @Override
    public void setPosition(int x, int y)
    {
        super.setPosition(x, y);
        this.dropdown.setPosition(x, y);
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY)
    {
        return this.dropdown.isMouseOver(mouseX, mouseY);
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        return this.dropdown.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        return this.dropdown.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        this.dropdown.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolledImpl(double mouseX, double mouseY,
                                       double horizontalAmount, double verticalAmount)
    {
        return this.dropdown.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected boolean onKeyTypedImpl(KeyEvent input)
    {
        return this.dropdown.onKeyTyped(input);
    }

    @Override
    protected boolean onCharTypedImpl(CharacterEvent input)
    {
        return this.dropdown.onCharTyped(input);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        this.dropdown.render(ctx, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        this.dropdown.postRenderHovered(ctx, mouseX, mouseY, selected);
    }
}
