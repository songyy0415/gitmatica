package me.arnavpmr.lvc.gui;

import me.arnavpmr.lvc.overlay.LvcTrackingOverlay;
import me.arnavpmr.lvc.git.LvcGitRemoteOps;
import me.arnavpmr.lvc.git.LvcGitHistoryOps;
import me.arnavpmr.lvc.git.LvcGitBranchOps;
import me.arnavpmr.lvc.git.LvcCommitInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcBranchActionMenu;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcBranchControls;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcCommitHistoryEntry;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcCommitHistoryList;
import me.arnavpmr.lvc.gui.widgets.WidgetLvcCommitMetadataPanel;
import me.arnavpmr.lvc.LvcReference;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcProjectManager
        extends GuiListBase<LvcCommitInfo, WidgetLvcCommitHistoryEntry, WidgetLvcCommitHistoryList>
        implements ICompletionListener, ISelectionListener<LvcCommitInfo>
{
    private static final GuiLvcProjectButtonType[] TOP_ACTIONS = {
            GuiLvcProjectButtonType.SAVE_VERSION,
            GuiLvcProjectButtonType.DISCARD_CHANGES,
            GuiLvcProjectButtonType.VIEW_CHANGES,
            GuiLvcProjectButtonType.CLEAR_AREA
    };
    private static final GuiLvcProjectButtonType[] BOTTOM_ACTIONS = {
            GuiLvcProjectButtonType.CHECKOUT_VERSION,
            GuiLvcProjectButtonType.VIEW_DIFFS,
            GuiLvcProjectButtonType.REVERT_CHANGES,
            GuiLvcProjectButtonType.EXPORT,
            GuiLvcProjectButtonType.LITEMATICA_MENU
    };
    private static final int PANEL_MARGIN = 10;
    private static final int TOP_BUTTON_Y = 28;
    private static final int BUTTON_ROW_GAP = 4;
    private static final int CONTENT_BUTTON_GAP = 8;
    private static final int SIDEBAR_METADATA_BUTTON_GAP = 8;
    private static final int SIDEBAR_BUTTON_HEIGHT = 20;
    private static final int SIDEBAR_BUTTON_STEP = 24;
    private static final int BRANCH_DROPDOWN_LEFT_GAP = 8;
    private static final int BRANCH_DROPDOWN_WIDTH = 92;
    private static final int BRANCH_DROPDOWN_VISIBLE_ROWS = 8;
    final Path repositoryDirectory;
    final String projectName;
    List<LvcCommitInfo> history = List.of();
    @Nullable LvcCommitInfo selectedCommit;
    @Nullable LvcTrackingOverlay trackingOverlay;
    @Nullable String remoteUrl;
    List<String> branchNames = List.of(LvcGitBranchOps.DEFAULT_BRANCH);
    @Nullable private WidgetLvcBranchActionMenu branchActionMenu;
    @Nullable private WidgetLvcBranchControls branchControls;
    @Nullable private WidgetLvcCommitMetadataPanel commitMetadataPanel;
    String trackingStatus = "";
    boolean detachedHead;
    boolean initialOverlayAttempted;
    String checkoutBranchName = LvcGitBranchOps.DEFAULT_BRANCH;
    String headPointerName = LvcGitBranchOps.DEFAULT_BRANCH;
    private int topActionRows = 1;
    private int bottomActionRows = 1;
    private final GuiLvcProjectController controller = new GuiLvcProjectController(this);

    public GuiLvcProjectManager(Path repositoryDirectory, String projectName)
    {
        super(PANEL_MARGIN, TOP_BUTTON_Y);
        this.repositoryDirectory = repositoryDirectory;
        this.projectName = projectName;
        this.updateTitle();
    }

    void handleHotkeyAction(GuiLvcProjectButtonType action)
    {
        if (this.controller.prepareHotkeyAction())
        {
            this.controller.focusTrackingOverlay();
            this.controller.handleButton(action);
        }
    }

    void handleHotkeyUndoLastSave()
    {
        if (this.controller.prepareHotkeyAction())
        {
            this.controller.focusTrackingOverlay();
            this.controller.promptDeleteLatestVersion();
        }
    }

    @Override
    public void initGui()
    {
        this.controller.refreshRepositoryState();
        this.branchControls = this.createBranchControls();
        this.topActionRows = this.calculateButtonRows(TOP_ACTIONS, true);
        this.bottomActionRows = this.calculateButtonRows(BOTTOM_ACTIONS, false);
        this.setListPosition(this.getHistoryPanelX(), this.getContentTopY());
        super.initGui();
        this.refreshHistory();
        this.branchActionMenu = null;
        this.commitMetadataPanel = this.addWidget(new WidgetLvcCommitMetadataPanel(
                this.getSidebarX(),
                this.getContentTopY(),
                this.getSidebarWidth(),
                this.getInfoPanelHeight(),
                () -> this.selectedCommit
        ));
        this.createWrappedButtonRow(TOP_BUTTON_Y, TOP_ACTIONS, true);
        this.createWrappedButtonRow(this.getBottomButtonY(), BOTTOM_ACTIONS, false);

        this.createSidebarButtons();
        this.controller.loadInitialTrackingOverlay();
    }

    @Override
    protected WidgetLvcCommitHistoryList createListWidget(int listX, int listY)
    {
        return new WidgetLvcCommitHistoryList(
                listX,
                listY,
                this.getBrowserWidth(),
                this.getBrowserHeight(),
                () -> this.history,
                () -> this.selectedCommit,
                this::onSelectionChange,
                this::canUndoCommit,
                this.controller::promptDeleteLatestVersion
        );
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getHistoryPanelWidth();
    }

    @Override
    protected int getBrowserHeight()
    {
        return Math.max(0, this.getContentBottomY() - this.getContentTopY());
    }

    @Override
    protected ISelectionListener<LvcCommitInfo> getSelectionListener()
    {
        return this;
    }

    @Override
    public void onSelectionChange(@Nullable LvcCommitInfo commit)
    {
        boolean changed = this.selectedCommit == null || commit == null ||
                !this.selectedCommit.id().equals(commit.id());
        this.selectedCommit = commit;

        if (changed && this.commitMetadataPanel != null)
        {
            this.commitMetadataPanel.resetScroll();
        }
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.branchActionMenu != null)
        {
            if (this.branchActionMenu.isMouseOver((int) click.x(), (int) click.y()))
            {
                return this.branchActionMenu.onMouseClicked(click, doubleClick);
            }

            if (!this.isMouseOverBranchDropdownButton((int) click.x(), (int) click.y()))
            {
                this.closeBranchActionMenu();
                return true;
            }

            this.closeBranchActionMenu();
        }

        if (this.branchControls != null && this.branchControls.handleModalClick(click, doubleClick))
        {
            return true;
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.branchActionMenu != null)
        {
            super.drawButtons(ctx, Integer.MIN_VALUE, Integer.MIN_VALUE, partialTicks);
            return;
        }

        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void drawTextFields(GuiContext ctx, int mouseX, int mouseY)
    {
        super.drawTextFields(ctx, mouseX, mouseY);

        if (this.branchControls != null)
        {
            this.branchControls.renderPopup(ctx, mouseX, mouseY);
        }

        if (this.branchActionMenu != null)
        {
            this.branchActionMenu.render(ctx, mouseX, mouseY, false);
        }
    }

    private int createButton(int x, int y, GuiLvcProjectButtonType type)
    {
        ButtonGeneric button = this.createButtonWidget(x, y, -1, type);
        this.addButton(button, this.controller.buttonListener(type));

        return button.getWidth() + BUTTON_ROW_GAP;
    }

    private void openBranchActionMenuFromDropdown()
    {
        if (this.branchActionMenu != null)
        {
            this.closeBranchActionMenu();
        }

        WidgetLvcBranchControls controls = this.branchControls;

        if (controls == null)
        {
            return;
        }

        this.blurBranchOverlayInputs();
        this.branchActionMenu = new WidgetLvcBranchActionMenu(
                controls.getDropdownX(),
                controls.getDropdownY(),
                controls.getDropdownWidth(),
                controls.getDropdownHeight(),
                this.getScreenWidth(),
                this.getScreenHeight(),
                this.controller::handleBranchMenuAction
        );
        this.closeBranchDropdown();
    }

    void closeBranchActionMenu()
    {
        this.branchActionMenu = null;
    }

    private void closeBranchDropdown()
    {
        if (this.branchControls != null)
        {
            this.branchControls.closePopup();
        }
    }

    void blurBranchOverlayInputs()
    {
        WidgetLvcCommitHistoryList historyList = this.getListWidget();

        if (historyList != null)
        {
            historyList.blurSearch();
        }
    }

    private boolean isMouseOverBranchDropdownButton(int mouseX, int mouseY)
    {
        return this.branchControls != null && this.branchControls.isMouseOverDropdownButton(mouseX, mouseY);
    }

    private int createRightButton(int rightX, int y, GuiLvcProjectButtonType type)
    {
        ButtonGeneric button = this.createButtonWidget(0, y, -1, type);
        button.setX(rightX - button.getWidth());
        this.addButton(button, this.controller.buttonListener(type));
        return button.getWidth();
    }

    private ButtonGeneric createButtonWidget(int x, int y, int width, GuiLvcProjectButtonType type)
    {
        String label = type.getLabel(this.checkoutBranchName);
        IGuiIcon icon = type.icon();
        ButtonGeneric button = icon != null ?
                new ButtonGeneric(x, y, width, SIDEBAR_BUTTON_HEIGHT, label, icon) :
                new ButtonGeneric(x, y, width, SIDEBAR_BUTTON_HEIGHT, label);

        if (type == GuiLvcProjectButtonType.CLOSE_PROJECT)
        {
            button.setTextCentered(false);
        }

        if (type == GuiLvcProjectButtonType.CHECKOUT_VERSION)
        {
            button.setHoverStrings("gitmatica.gui.button.hover.lvc_project.load_version_soft");
        }

        if (this.isDulledAction(type))
        {
            button.setEnabled(false);
        }

        return button;
    }

    private int calculateButtonRows(GuiLvcProjectButtonType[] types, boolean includeBranchControls)
    {
        int startX = this.getHistoryPanelVisibleLeftX();
        int maxRightX = this.getScreenWidth() - PANEL_MARGIN;
        int x = startX;
        int rows = 1;

        for (GuiLvcProjectButtonType type : types)
        {
            int width = this.getButtonWidth(type);

            if (this.isRightAlignedAction(type))
            {
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    rows++;
                    x = startX;
                }

                x = alignedX + width + BUTTON_ROW_GAP;
                continue;
            }

            if (x > startX && x + width > maxRightX)
            {
                rows++;
                x = startX;
            }

            x += width + BUTTON_ROW_GAP;
        }

        if (includeBranchControls && this.branchControls != null)
        {
            int alignedX = this.getRightAlignedActionX(this.branchControls.getWidth());

            if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
            {
                rows++;
            }
        }

        return rows;
    }

    private void createWrappedButtonRow(int startY, GuiLvcProjectButtonType[] types,
                                        boolean includeBranchControls)
    {
        int startX = this.getHistoryPanelVisibleLeftX();
        int maxRightX = this.getScreenWidth() - PANEL_MARGIN;
        int x = startX;
        int y = startY;

        for (GuiLvcProjectButtonType type : types)
        {
            int width = this.getButtonWidth(type);

            if (this.isRightAlignedAction(type))
            {
                int alignedX = this.getRightAlignedActionX(width);

                if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
                {
                    x = startX;
                    y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
                }

                this.createRightButton(alignedX + width, y, type);

                x = alignedX + width + BUTTON_ROW_GAP;
                continue;
            }

            if (x > startX && x + width > maxRightX)
            {
                x = startX;
                y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
            }

            x += this.createButton(x, y, type);
        }

        if (includeBranchControls && this.branchControls != null)
        {
            int alignedX = this.getRightAlignedActionX(this.branchControls.getWidth());

            if (x > startX && x + BRANCH_DROPDOWN_LEFT_GAP > alignedX)
            {
                y += SIDEBAR_BUTTON_HEIGHT + BUTTON_ROW_GAP;
            }

            this.branchControls.setPosition(alignedX, y);
            this.addWidget(this.branchControls);
        }
    }

    private int getButtonWidth(GuiLvcProjectButtonType type)
    {
        return this.createButtonWidget(0, 0, -1, type).getWidth();
    }

    private WidgetLvcBranchControls createBranchControls()
    {
        WidgetLvcBranchControls controls = new WidgetLvcBranchControls(
                0,
                0,
                SIDEBAR_BUTTON_HEIGHT,
                BUTTON_ROW_GAP,
                BRANCH_DROPDOWN_WIDTH,
                BRANCH_DROPDOWN_VISIBLE_ROWS,
                GuiLvcProjectButtonType.PUSH.getLabel(this.checkoutBranchName),
                GuiLvcProjectButtonType.PULL.getLabel(this.checkoutBranchName),
                this.branchNames,
                this.currentBranchDropdownName(),
                this.currentCheckedBranchName(),
                this.controller.buttonListener(GuiLvcProjectButtonType.PUSH),
                this.controller.buttonListener(GuiLvcProjectButtonType.PULL),
                this.controller::handleBranchDropdownSelection,
                this::openBranchActionMenuFromDropdown
        );
        controls.setPushEnabled(false);
        controls.setPullEnabled(false);
        controls.setDetachedHead(this.detachedHead, this.headPointerName);
        return controls;
    }

    private boolean isDulledAction(GuiLvcProjectButtonType type)
    {
        return type == GuiLvcProjectButtonType.PUSH ||
                type == GuiLvcProjectButtonType.PULL ||
                type == GuiLvcProjectButtonType.VIEW_DIFFS ||
                type == GuiLvcProjectButtonType.REVERT_CHANGES;
    }

    private boolean isRightAlignedAction(GuiLvcProjectButtonType type)
    {
        return type == GuiLvcProjectButtonType.LITEMATICA_MENU;
    }

    private int getRightAlignedActionX(int width)
    {
        int rightX = this.getSidebarX() + this.getSidebarWidth();
        return Math.max(this.getHistoryPanelVisibleLeftX(), rightX - width);
    }

    private void createSidebarButtons()
    {
        int y = this.getSidebarActionsY();
        int width = this.createButtonWidget(0, 0, -1, GuiLvcProjectButtonType.PROJECT_SETTINGS).getWidth();
        int x = this.getSidebarX() - 1;

        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.PROJECT_EDITOR), this.controller.buttonListener(GuiLvcProjectButtonType.PROJECT_EDITOR));
        y += SIDEBAR_BUTTON_STEP;
        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.PROJECT_SETTINGS), this.controller.buttonListener(GuiLvcProjectButtonType.PROJECT_SETTINGS));
        y += SIDEBAR_BUTTON_STEP;
        this.addButton(this.createButtonWidget(x, y, width, GuiLvcProjectButtonType.CLOSE_PROJECT), this.controller.buttonListener(GuiLvcProjectButtonType.CLOSE_PROJECT));
    }

    private int getBottomButtonY()
    {
        return this.getScreenHeight() - CONTENT_BUTTON_GAP / 2 - this.getButtonRowsHeight(this.bottomActionRows);
    }

    int getContentBottomY()
    {
        return this.getBottomButtonY() - 8;
    }

    int getContentTopY()
    {
        return TOP_BUTTON_Y + this.getButtonRowsHeight(this.topActionRows) + CONTENT_BUTTON_GAP;
    }

    private int getButtonRowsHeight(int rows)
    {
        return rows * SIDEBAR_BUTTON_HEIGHT + Math.max(0, rows - 1) * BUTTON_ROW_GAP;
    }

    int getHistoryPanelX()
    {
        return PANEL_MARGIN;
    }

    private int getHistoryPanelVisibleLeftX()
    {
        return this.getHistoryPanelX() - 1;
    }

    int getHistoryPanelWidth()
    {
        return Math.max(40, this.getSidebarX() - this.getHistoryPanelX() - 8);
    }

    int getSidebarWidth()
    {
        int availableWidth = Math.max(0, this.getScreenWidth() - PANEL_MARGIN * 2);

        if (availableWidth < 380)
        {
            return Math.max(96, availableWidth / 2);
        }

        return Math.clamp(this.getScreenWidth() / 3, 190, 260);
    }

    int getSidebarX()
    {
        return this.getScreenWidth() - this.getSidebarWidth() - PANEL_MARGIN;
    }

    private int getSidebarActionsY()
    {
        int actionY = this.getContentBottomY() - SIDEBAR_BUTTON_HEIGHT - SIDEBAR_BUTTON_STEP * 2 + 1;
        return Math.max(this.getContentTopY(), actionY);
    }

    int getInfoPanelHeight()
    {
        return Math.max(0, this.getSidebarActionsY() - this.getContentTopY() - SIDEBAR_METADATA_BUTTON_GAP);
    }

    private void refreshRepositoryState()
    {
        this.detachedHead = false;
        this.checkoutBranchName = LvcGitBranchOps.DEFAULT_BRANCH;
        this.headPointerName = LvcGitBranchOps.DEFAULT_BRANCH;
        this.remoteUrl = null;
        this.branchNames = List.of(LvcGitBranchOps.DEFAULT_BRANCH);

        try
        {
            this.detachedHead = LvcGitBranchOps.isDetachedHead(this.repositoryDirectory);
            this.remoteUrl = LvcGitRemoteOps.remoteOriginUrl(this.repositoryDirectory);
            this.checkoutBranchName = LvcGitBranchOps.preferredCheckoutBranchName(this.repositoryDirectory);
            this.headPointerName = LvcGitBranchOps.headPointerName(this.repositoryDirectory);
            this.setBranchNames(LvcGitBranchOps.listLocalBranches(this.repositoryDirectory));
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.history_failed", e.getMessage());
        }

        this.updateTitle();
    }

    void setBranchNames(List<String> branchNames)
    {
        List<String> normalizedBranches = new ArrayList<>();

        for (String branchName : branchNames)
        {
            if (branchName != null && !branchName.isBlank() && !normalizedBranches.contains(branchName))
            {
                normalizedBranches.add(branchName);
            }
        }

        if (!this.detachedHead)
        {
            this.addFallbackBranchName(normalizedBranches, this.headPointerName);
        }

        this.addFallbackBranchName(normalizedBranches, this.checkoutBranchName);

        if (normalizedBranches.isEmpty())
        {
            normalizedBranches.add(LvcGitBranchOps.DEFAULT_BRANCH);
        }

        this.branchNames = List.copyOf(normalizedBranches);
        this.syncBranchDropdownSelection();
    }

    private void addFallbackBranchName(List<String> branchNames, String branchName)
    {
        if (branchName != null && !branchName.isBlank() && !"unknown".equals(branchName) && !branchNames.contains(branchName))
        {
            branchNames.add(branchName);
        }
    }

    String currentBranchDropdownName()
    {
        if (!this.detachedHead && this.branchNames.contains(this.headPointerName))
        {
            return this.headPointerName;
        }

        if (this.branchNames.contains(this.checkoutBranchName))
        {
            return this.checkoutBranchName;
        }

        return this.branchNames.isEmpty() ? LvcGitBranchOps.DEFAULT_BRANCH : this.branchNames.get(0);
    }

    private String currentCheckedBranchName()
    {
        if (!this.detachedHead && this.branchNames.contains(this.headPointerName))
        {
            return this.headPointerName;
        }

        return this.currentBranchDropdownName();
    }

    void syncBranchDropdownSelection()
    {
        if (this.branchControls != null)
        {
            this.branchControls.setBranches(this.branchNames);
            this.branchControls.setSelectedBranch(this.currentBranchDropdownName());
            this.branchControls.setHeadBranch(this.currentCheckedBranchName());
            this.branchControls.setDetachedHead(this.detachedHead, this.headPointerName);
        }
    }

    void updateTitle()
    {
        String displayedProjectName = this.ellipsizeTitleProjectName(this.projectName);
        this.title = StringUtils.translate("gitmatica.gui.title.lvc_project", LvcReference.MOD_VERSION, displayedProjectName);

        if (!displayedProjectName.equals(this.projectName))
        {
            LvcDiagnostics.debug("GuiLvcProjectManager: clipped title project name repo='{}' project='{}' displayed='{}'",
                    this.repositoryDirectory, this.projectName, displayedProjectName);
        }
    }

    private String ellipsizeTitleProjectName(String projectName)
    {
        int maxTitleWidth = this.getScreenWidth() - PANEL_MARGIN * 2;

        if (maxTitleWidth <= 0 || this.getStringWidth(this.projectTitle(projectName)) <= maxTitleWidth)
        {
            return projectName;
        }

        String suffix = "...";
        int suffixWidth = this.getStringWidth(suffix);

        if (suffixWidth > maxTitleWidth)
        {
            return "";
        }

        for (int length = projectName.length(); length > 0; length--)
        {
            String candidate = projectName.substring(0, length) + suffix;

            if (this.getStringWidth(this.projectTitle(candidate)) <= maxTitleWidth)
            {
                return candidate;
            }
        }

        return suffix;
    }

    private String projectTitle(String projectName)
    {
        return StringUtils.translate("gitmatica.gui.title.lvc_project", LvcReference.MOD_VERSION, projectName);
    }

    void refreshHistory()
    {
        WidgetLvcCommitHistoryList historyList = this.getListWidget();

        try
        {
            this.history = LvcGitHistoryOps.listCommits(this.repositoryDirectory);
            historyList.refreshHistory();
        }
        catch (Exception e)
        {
            this.history = List.of();
            this.selectedCommit = null;
            historyList.refreshHistory();

            if (this.commitMetadataPanel != null)
            {
                this.commitMetadataPanel.resetScroll();
            }

            this.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.history_failed", e.getMessage());
        }
    }

    void focusHistoryCommitAfterNextRefresh(String commitId)
    {
        this.getListWidget().focusCommitAfterNextRefresh(commitId);
    }

    boolean hasHistory()
    {
        return !this.history.isEmpty();
    }

    @Override
    public void onTaskCompleted()
    {
        if (this.trackingOverlay == null)
        {
            return;
        }

        int errors = this.trackingOverlay.verifier().getTotalErrors();
        this.trackingStatus = errors == 0 ?
                StringUtils.translate("gitmatica.gui.label.lvc_project.tracking_clean") :
                StringUtils.translate("gitmatica.gui.label.lvc_project.tracking_dirty", errors);
    }

    @Override
    public void onTaskAborted()
    {
        this.trackingStatus = StringUtils.translate("gitmatica.gui.label.lvc_project.tracking_aborted");
    }

    private boolean canUndoCommit(LvcCommitInfo commit)
    {
        return !this.detachedHead &&
                this.history.size() > 1 &&
                !this.history.isEmpty() &&
                this.history.get(0).id().equals(commit.id());
    }

}
