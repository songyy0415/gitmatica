package me.arnavpmr.lvc.gui.widgets;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetLvcBranchControls extends fi.dy.masa.malilib.gui.widgets.WidgetBase
{
    private static final int RIGHT_MOUSE_BUTTON = 1;

    private final int gap;
    private final ButtonGeneric pushButton;
    private final ButtonGeneric pullButton;
    private final WidgetLvcBranchDropdown dropdown;
    private final WidgetLvcPopupHost popupHost;
    private final Runnable actionMenuConsumer;

    public WidgetLvcBranchControls(int x, int y, int height, int gap, int dropdownWidth,
                                   int maxVisibleRows, String pushLabel, String pullLabel,
                                   List<String> branches, String selectedBranch, String headBranch,
                                   IButtonActionListener pushListener, IButtonActionListener pullListener,
                                   Consumer<String> branchSelectionConsumer, Runnable actionMenuConsumer)
    {
        super(x, y, requiredWidth(pushLabel, pullLabel, gap, dropdownWidth), height);
        this.gap = gap;
        this.actionMenuConsumer = actionMenuConsumer;
        this.pushButton = new ButtonGeneric(x, y, -1, height, pushLabel);
        this.pushButton.setActionListener(pushListener);
        this.pullButton = new ButtonGeneric(x + this.pushButton.getWidth() + gap, y, -1, height, pullLabel);
        this.pullButton.setActionListener(pullListener);
        this.dropdown = new WidgetLvcBranchDropdown(
                this.pullButton.getX() + this.pullButton.getWidth() + gap,
                y,
                dropdownWidth,
                height,
                maxVisibleRows,
                branches,
                selectedBranch,
                headBranch,
                branchSelectionConsumer
        );
        this.popupHost = new WidgetLvcPopupHost(this.dropdown);
    }

    public void setPushEnabled(boolean enabled)
    {
        this.pushButton.setEnabled(enabled);
    }

    public void setPullEnabled(boolean enabled)
    {
        this.pullButton.setEnabled(enabled);
    }

    public void setBranches(List<String> branches)
    {
        this.dropdown.setBranches(branches);
    }

    public void setSelectedBranch(String branch)
    {
        this.dropdown.setSelectedBranch(branch);
    }

    public void setHeadBranch(String branch)
    {
        this.dropdown.setHeadBranch(branch);
    }

    public void setDetachedHead(boolean detachedHead, String commitId)
    {
        this.dropdown.setDetachedHead(detachedHead, commitId);
    }

    public void closePopup()
    {
        this.popupHost.close();
    }

    public boolean handleModalClick(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.isDropdownButtonRightClick(click))
        {
            this.closePopup();
            this.actionMenuConsumer.run();
            return true;
        }

        return this.popupHost.handleModalClick(click, doubleClick);
    }

    public boolean isMouseOverDropdownButton(int mouseX, int mouseY)
    {
        return mouseX >= this.dropdown.getX() && mouseX < this.dropdown.getX() + this.dropdown.getWidth() &&
                mouseY >= this.dropdown.getY() && mouseY < this.dropdown.getY() + this.dropdown.getHeight();
    }

    public int getDropdownX()
    {
        return this.dropdown.getX();
    }

    public int getDropdownY()
    {
        return this.dropdown.getY();
    }

    public int getDropdownWidth()
    {
        return this.dropdown.getWidth();
    }

    public int getDropdownHeight()
    {
        return this.dropdown.getHeight();
    }

    public void renderPopup(GuiContext ctx, int mouseX, int mouseY)
    {
        this.popupHost.renderPopup(ctx, mouseX, mouseY);
    }

    @Override
    public void setPosition(int x, int y)
    {
        super.setPosition(x, y);
        this.pushButton.setPosition(x, y);
        this.pullButton.setPosition(x + this.pushButton.getWidth() + this.gap, y);
        this.popupHost.setPosition(this.pullButton.getX() + this.pullButton.getWidth() + this.gap, y);
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY)
    {
        return super.isMouseOver(mouseX, mouseY) || this.popupHost.isMouseOver(mouseX, mouseY);
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.isDropdownButtonRightClick(click))
        {
            this.actionMenuConsumer.run();
            return true;
        }

        if (this.popupHost.handleModalClick(click, doubleClick))
        {
            return true;
        }

        if (this.pushButton.isMouseOver((int) click.x(), (int) click.y()))
        {
            return this.pushButton.onMouseClicked(click, doubleClick);
        }

        if (this.pullButton.isMouseOver((int) click.x(), (int) click.y()))
        {
            return this.pullButton.onMouseClicked(click, doubleClick);
        }

        return this.popupHost.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragXAmount, double dragYAmount)
    {
        return this.popupHost.onMouseDragged(click, dragXAmount, dragYAmount);
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click)
    {
        this.pushButton.onMouseReleased(click);
        this.pullButton.onMouseReleased(click);
        this.popupHost.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolledImpl(double mouseX, double mouseY,
                                       double horizontalAmount, double verticalAmount)
    {
        return this.popupHost.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected boolean onKeyTypedImpl(KeyEvent input)
    {
        return this.popupHost.onKeyTyped(input);
    }

    @Override
    protected boolean onCharTypedImpl(CharacterEvent input)
    {
        return this.popupHost.onCharTyped(input);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        this.pushButton.render(ctx, mouseX, mouseY, false);
        this.pullButton.render(ctx, mouseX, mouseY, false);
        this.popupHost.render(ctx, mouseX, mouseY, false);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        this.pushButton.postRenderHovered(ctx, mouseX, mouseY, false);
        this.pullButton.postRenderHovered(ctx, mouseX, mouseY, false);
        this.popupHost.postRenderHovered(ctx, mouseX, mouseY, false);
    }

    private boolean isDropdownButtonRightClick(MouseButtonEvent click)
    {
        return click.input() == RIGHT_MOUSE_BUTTON &&
                this.isMouseOverDropdownButton((int) click.x(), (int) click.y());
    }

    private static int requiredWidth(String pushLabel, String pullLabel, int gap, int dropdownWidth)
    {
        return StringUtils.getStringWidth(pushLabel) + 10 +
                StringUtils.getStringWidth(pullLabel) + 10 +
                dropdownWidth + gap * 2;
    }
}
