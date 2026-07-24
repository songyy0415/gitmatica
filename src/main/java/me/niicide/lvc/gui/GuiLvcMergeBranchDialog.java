package me.niicide.lvc.gui;

import java.util.List;
import javax.annotation.Nullable;
import me.niicide.lvc.gui.widgets.WidgetLvcBranchDropdown;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcMergeBranchDialog extends GuiLvcPanelDialog
{
    private static final int DIALOG_WIDTH = 244;
    private static final int DIALOG_HEIGHT = 98;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_TEXTURE_LEFT_INSET = 2;
    private static final int DROPDOWN_HEIGHT = 20;
    private static final int DROPDOWN_VISIBLE_ROWS = 6;

    private final GuiLvcProjectController controller;
    private final List<String> branches;
    @Nullable private WidgetLvcBranchDropdown branchDropdown;
    @Nullable private String selectedBranch;

    GuiLvcMergeBranchDialog(GuiLvcProjectController controller, List<String> branches)
    {
        super(controller.gui, "gitmatica.gui.title.lvc_project.merge_branch",
                DIALOG_WIDTH, DIALOG_HEIGHT, PADDING);
        this.controller = controller;
        this.branches = WidgetLvcBranchDropdown.normalizeBranches(branches);
        this.selectedBranch = this.branches.isEmpty() ? null : this.branches.get(0);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.setWidthAndHeight(DIALOG_WIDTH, DIALOG_HEIGHT);
        this.centerOnScreen();

        int dropdownX = this.dialogLeft + PADDING;
        int dropdownY = this.dialogTop + 38;
        int dropdownWidth = this.dialogWidth - PADDING * 2;
        this.branchDropdown = this.addPopup(new WidgetLvcBranchDropdown(
                dropdownX,
                dropdownY,
                dropdownWidth,
                DROPDOWN_HEIGHT,
                DROPDOWN_VISIBLE_ROWS,
                this.branches,
                this.selectedBranch,
                null,
                branchName -> this.selectedBranch = branchName
        ));

        this.createButtons();
    }

    @Override
    protected void drawPanelContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        this.drawStringWithShadow(ctx, StringUtils.translate("gitmatica.gui.label.lvc_project.merge_branch_select"),
                this.dialogLeft + PADDING, this.dialogTop + 24, COLOR_WHITE);
    }

    private void createButtons()
    {
        String mergeLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.branch_action_merge");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int mergeWidth = Math.max(72, this.getStringWidth(mergeLabel) + 18);
        int cancelWidth = Math.max(60, this.getStringWidth(cancelLabel) + 18);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int mergeX = this.getDropdownAlignedButtonX();
        int cancelX = mergeX + mergeWidth + BUTTON_GAP;

        this.addButton(new ButtonGeneric(mergeX, y, mergeWidth, BUTTON_HEIGHT, mergeLabel),
                (button, mouseButton) -> this.mergeSelectedBranch());
        this.addButton(new ButtonGeneric(cancelX, y, cancelWidth, BUTTON_HEIGHT, cancelLabel),
                (button, mouseButton) -> this.closeGui(true));
    }

    private int getDropdownAlignedButtonX()
    {
        int dropdownX = this.branchDropdown != null ? this.branchDropdown.getX() : this.dialogLeft + PADDING;
        return dropdownX - BUTTON_TEXTURE_LEFT_INSET;
    }

    private void mergeSelectedBranch()
    {
        if (this.controller.mergeBranch(this.selectedBranch))
        {
            this.closeGui(true);
        }
    }

}
