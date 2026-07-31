package me.arnavpmr.lvc.gui;

import java.util.List;
import javax.annotation.Nullable;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcBranchDropdown;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcDeleteBranchDialog extends GuiLvcPanelDialog
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

    GuiLvcDeleteBranchDialog(GuiLvcProjectController controller, List<String> branches)
    {
        super(controller.gui, "gitmatica.gui.title.lvc_project.delete_branch",
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
        this.drawStringWithShadow(ctx, StringUtils.translate("gitmatica.gui.label.lvc_project.delete_branch_select"),
                this.dialogLeft + PADDING, this.dialogTop + 24, COLOR_WHITE);
    }

    private void createButtons()
    {
        String deleteLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.branch_action_delete");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int deleteWidth = Math.max(72, this.getStringWidth(deleteLabel) + 18);
        int cancelWidth = Math.max(60, this.getStringWidth(cancelLabel) + 18);
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int deleteX = this.getDropdownAlignedButtonX();
        int cancelX = deleteX + deleteWidth + BUTTON_GAP;

        this.addButton(new ButtonGeneric(deleteX, y, deleteWidth, BUTTON_HEIGHT, deleteLabel),
                (button, mouseButton) -> this.deleteSelectedBranch());
        this.addButton(new ButtonGeneric(cancelX, y, cancelWidth, BUTTON_HEIGHT, cancelLabel),
                (button, mouseButton) -> this.closeGui(true));
    }

    private int getDropdownAlignedButtonX()
    {
        int dropdownX = this.branchDropdown != null ? this.branchDropdown.getX() : this.dialogLeft + PADDING;
        return dropdownX - BUTTON_TEXTURE_LEFT_INSET;
    }

    private void deleteSelectedBranch()
    {
        String deletedBranchName = this.controller.deleteBranch(this.selectedBranch);

        if (deletedBranchName != null)
        {
            this.closeGui(true);
            LvcGuiMessages.show(MessageType.SUCCESS, "gitmatica.message.lvc_project.branch_deleted", deletedBranchName);
        }
    }

}
