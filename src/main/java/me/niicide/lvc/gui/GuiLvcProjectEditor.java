package me.niicide.lvc.gui;

import me.niicide.lvc.semantic.LvcSemanticProjectEditor;
import me.niicide.lvc.semantic.LvcProjectEditorState;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.gui.widgets.WidgetLvcBlockPosEditor;
import me.niicide.lvc.gui.widgets.WidgetLvcProjectSubRegion;
import me.niicide.lvc.gui.widgets.WidgetLvcProjectSubRegionList;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.LvcReference;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcProjectEditor extends GuiListBase<LvcManifest.Region, WidgetLvcProjectSubRegion, WidgetLvcProjectSubRegionList>
        implements ISelectionListener<LvcManifest.Region>
{
    private static final int MARGIN = 10;
    private static final int SUB_REGION_LIST_X = MARGIN - 2;
    private static final int TOP_BUTTON_X = LEFT - 9;
    private static final int FORM_X = TOP_BUTTON_X + 3;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GROUP_GAP = 4;
    private static final int TEXT_FIELD_HEIGHT = 16;
    private static final int COORDINATE_TOP_Y = TOP - 5;
    private static final int COORDINATE_GROUP_WIDTH = 168;
    private static final int COORDINATE_GROUP_LEFT_GAP = 16;
    private static final int TITLE_COLUMN_GAP = 8;
    private static final int TITLE_TOOLTIP_MAX_WIDTH = 220;
    private static final int TITLE_TOOLTIP_SCREEN_PADDING = 32;
    private static final int PROJECT_NAME_FIELD_WIDTH = 286;
    private static final int PROJECT_NAME_TOP_GAP = 4;
    private static final int ACTION_BUTTON_TOP_GAP = 4;
    private static final int STATUS_COLOR = 0xFFAAAAAA;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int SUCCESS_COLOR = 0xFF55FF55;
    private static final int INPUT_FILL_COLOR = 0xE0000000;
    private static final int INPUT_OUTLINE_COLOR = 0xFF808080;
    private static final int READ_ONLY_TEXT_COLOR = 0xFFFFFFFF;

    private final Path repositoryDirectory;
    private String projectName;
    @Nullable private LvcProjectEditorState state;
    @Nullable private WidgetLvcBlockPosEditor originEditor;
    @Nullable private String selectedRegionId;
    private String statusText = "";
    private int statusColor = STATUS_COLOR;

    public GuiLvcProjectEditor(Path repositoryDirectory, String projectName)
    {
        super(MARGIN, TOP_Y + 128);
        this.repositoryDirectory = repositoryDirectory;
        this.projectName = projectName;
        this.title = StringUtils.translate("gitmatica.gui.title.lvc_project_editor", LvcReference.MOD_VERSION, projectName);
    }

    @Override
    public void initGui()
    {
        this.originEditor = null;
        this.refreshState();
        this.setListPosition(SUB_REGION_LIST_X, this.getSubRegionListY());
        super.initGui();

        if (this.state != null)
        {
            this.createTopButtons();
            this.createOriginEditor();
        }

        this.createBottomButtons();
    }

    @Override
    protected WidgetLvcProjectSubRegionList createListWidget(int listX, int listY)
    {
        List<LvcManifest.Region> regions = this.state != null ? this.state.regions() : List.of();
        return new WidgetLvcProjectSubRegionList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), regions, this, this);
    }

    @Override
    protected int getBrowserWidth()
    {
        return Math.max(120, this.getScreenWidth() - SUB_REGION_LIST_X - MARGIN);
    }

    @Override
    protected int getBrowserHeight()
    {
        return Math.max(44, this.getBottomButtonY() - this.getSubRegionListY() - 8);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.state == null)
        {
            ctx.drawString(ctx.fontRenderer(),
                    LvcGuiText.ellipsizeToWidth(this.statusText, this.getScreenWidth() - MARGIN * 2, this::getStringWidth),
                    MARGIN, TOP_Y, this.statusColor, false);
            return;
        }

        this.drawProjectInfo(ctx);
        this.drawSubRegionLabel(ctx);
        this.drawEmptySubRegionMessage(ctx);
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        this.drawStatus(ctx);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.originEditor != null)
        {
            this.originEditor.blurIfOutside(click);
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        ctx.drawString(ctx.fontRenderer(), this.getRenderedScreenTitle(), LEFT, TOP, COLOR_WHITE, false);
    }

    @Override
    protected void drawHoveredWidget(GuiContext ctx, int mouseX, int mouseY)
    {
        super.drawHoveredWidget(ctx, mouseX, mouseY);

        String fullTitle = this.getTitleString();
        String renderedTitle = this.getRenderedScreenTitle();

        if (!renderedTitle.isEmpty() && !renderedTitle.equals(fullTitle) &&
                GuiBase.isMouseOver(mouseX, mouseY, LEFT, TOP, this.getStringWidth(renderedTitle), this.fontHeight))
        {
            int availableWidth = GuiUtils.getScaledWindowWidth() - TITLE_TOOLTIP_SCREEN_PADDING;
            int maxWidth = Math.max(80, Math.min(TITLE_TOOLTIP_MAX_WIDTH, availableWidth));
            RenderUtils.drawHoverText(ctx, mouseX, mouseY,
                    LvcGuiText.wrapTextToWidth(fullTitle, maxWidth, this::getStringWidth));
        }
    }

    private String getRenderedScreenTitle()
    {
        return LvcGuiText.ellipsizeToWidth(
                this.getTitleString(),
                this.getScreenTitleMaxWidth(),
                this::getStringWidth
        );
    }

    private int getScreenTitleMaxWidth()
    {
        int maxWidth = this.getScreenWidth() - LEFT - MARGIN;

        if (this.state != null && this.worldOriginFitsProjectRow())
        {
            maxWidth = Math.min(maxWidth, this.getOriginGroupX() - LEFT - TITLE_COLUMN_GAP);
        }

        return Math.max(0, maxWidth);
    }

    private void refreshState()
    {
        try
        {
            this.state = LvcSemanticProjectEditor.readState(this.repositoryDirectory);
            this.projectName = this.state.projectName();
            this.title = StringUtils.translate("gitmatica.gui.title.lvc_project_editor", LvcReference.MOD_VERSION, this.projectName);
            this.ensureSelectedRegionExists();
            LvcDiagnostics.debug("GuiLvcProjectEditor: ui state loaded repo='{}' project='{}' regions={} placementOrigin='{}'",
                    this.repositoryDirectory, this.projectName, this.state.regions().size(), this.state.placementOrigin());
        }
        catch (Exception e)
        {
            this.state = null;
            this.setErrorStatus(e.getMessage());
            LvcDiagnostics.warn("GuiLvcProjectEditor: failed to load ui state repo='{}' error='{}'",
                    this.repositoryDirectory, e.getMessage());
        }
    }

    private void ensureSelectedRegionExists()
    {
        if (this.state == null)
        {
            this.selectedRegionId = null;
            return;
        }

        if (this.selectedRegionId != null)
        {
            for (LvcManifest.Region region : this.state.regions())
            {
                if (region.id().equals(this.selectedRegionId))
                {
                    return;
                }
            }
        }

        this.selectedRegionId = this.state.regions().isEmpty() ? null : this.state.regions().get(0).id();
    }

    private void createTopButtons()
    {
        int x = TOP_BUTTON_X;
        int y = TOP_Y;
        int maxX = this.getTopControlsMaxX();

        x = this.createWrappedButton(x, y, TOP_BUTTON_X, maxX, ButtonType.CHANGE_SELECTION_MODE);
        this.createWrappedButton(x + BUTTON_GROUP_GAP, y, TOP_BUTTON_X, maxX, ButtonType.CHANGE_CORNER_MODE);

        y = this.getActionButtonY();
        x = TOP_BUTTON_X;
        x = this.createWrappedButton(x, y, TOP_BUTTON_X, this.getScreenWidth() - MARGIN, ButtonType.NEW_SUB_REGION);
        x = this.createWrappedButton(x + BUTTON_GROUP_GAP, y, TOP_BUTTON_X, this.getScreenWidth() - MARGIN, ButtonType.SAVE_VERSION);
        this.createManualOriginButton(x + BUTTON_GROUP_GAP, y);
    }

    private int createWrappedButton(int x, int y, int minX, int maxX, ButtonType type)
    {
        String label = type.getDisplayName();
        int width = this.getStringWidth(label) + 10;

        if (x > minX && x + width > maxX)
        {
            x = minX;
            y += BUTTON_HEIGHT + 2;
        }

        return x + this.createButton(x, y, width, type);
    }

    private void createManualOriginButton(int x, int y)
    {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, false, "gitmatica.gui.button.lvc_project_editor.manual_origin", false);
        button.setEnabled(false);
        this.addButton(button, new ButtonListener(this, ButtonType.MANUAL_ORIGIN));
    }

    private void createOriginEditor()
    {
        this.originEditor = this.addWidget(new WidgetLvcBlockPosEditor(
                this.getOriginGroupX(),
                this.getCoordinateGroupY(),
                COORDINATE_GROUP_WIDTH,
                StringUtils.translate("gitmatica.gui.label.lvc_project_editor.placement_origin"),
                ButtonType.SET_ORIGIN_TO_PLAYER.getDisplayName(),
                this.state.placementOrigin(),
                this::updatePlacementOrigin,
                () -> this.setErrorStatus(StringUtils.translate("gitmatica.error.lvc_project_editor.invalid_integer")),
                this::setOriginToPlayer
        ));
    }

    private void createBottomButtons()
    {
        this.createButton(MARGIN, this.getBottomButtonY(), ButtonType.ANALYZE_AREA);

        int rightX = this.getScreenWidth() - MARGIN;
        int menuWidth = this.createRightAlignedBottomButton(rightX, ButtonType.LITEMATICA_MENU);
        this.createRightAlignedBottomButton(rightX - menuWidth - BUTTON_GROUP_GAP, ButtonType.PROJECT_MANAGER);
    }

    private int createRightAlignedBottomButton(int rightX, ButtonType type)
    {
        int width = this.getBottomNavigationButtonWidth(type);
        this.createButton(rightX - width, this.getBottomButtonY(), width, type);
        return width;
    }

    private int createButton(int x, int y, ButtonType type)
    {
        return this.createButton(x, y, -1, type);
    }

    private int createButton(int x, int y, int width, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, width, BUTTON_HEIGHT, type.getDisplayName());
        button.setEnabled(type.isEnabled());
        this.addButton(button, new ButtonListener(this, type));
        return button.getWidth();
    }

    private void drawProjectInfo(GuiContext ctx)
    {
        int labelY = this.getProjectNameLabelY();
        int fieldY = this.getProjectNameFieldY();
        int fieldWidth = this.getProjectNameFieldWidth();

        ctx.drawString(ctx.fontRenderer(), StringUtils.translate("gitmatica.gui.label.lvc_project_editor.project_name"), FORM_X, labelY, 0xFFFFFFFF, false);
        RenderUtils.drawOutlinedBox(ctx, FORM_X, fieldY, fieldWidth, TEXT_FIELD_HEIGHT, INPUT_FILL_COLOR, INPUT_OUTLINE_COLOR);
        ctx.drawString(ctx.fontRenderer(),
                LvcGuiText.ellipsizeToWidth(this.projectName, fieldWidth - 6, this::getStringWidth),
                FORM_X + 3, fieldY + 4, READ_ONLY_TEXT_COLOR, false);
    }

    private void drawSubRegionLabel(GuiContext ctx)
    {
        int y = this.getSubRegionLabelY();
        String count = this.state == null ? "0" : String.valueOf(this.state.regions().size());
        ctx.drawString(ctx.fontRenderer(), GuiBase.TXT_BOLD + StringUtils.translate("gitmatica.gui.label.lvc_project_editor.sub_regions", count),
                MARGIN, y, 0xFFFFFFFF, false);
    }

    private void drawEmptySubRegionMessage(GuiContext ctx)
    {
        if (this.state != null && this.state.regions().isEmpty())
        {
            int y = this.getSubRegionListY() + 24;
            ctx.drawString(ctx.fontRenderer(), StringUtils.translate("gitmatica.gui.label.lvc_project_editor.no_sub_regions"),
                    MARGIN + 6, y, STATUS_COLOR, false);
        }
    }

    private void drawStatus(GuiContext ctx)
    {
        if (!this.statusText.isBlank())
        {
            int maxWidth = Math.max(40,
                    this.getScreenWidth() - this.getBottomNavigationWidth() - MARGIN * 3 -
                            this.getStringWidth(StringUtils.translate("litematica.gui.button.area_editor.analyze_area")));
            ctx.drawString(ctx.fontRenderer(),
                    LvcGuiText.ellipsizeToWidth(this.statusText, maxWidth, this::getStringWidth),
                    MARGIN, this.getBottomButtonY() + 6, this.statusColor, false);
        }
    }

    private void openProjectManager()
    {
        GuiBase.openGui(new GuiLvcProjectManager(this.repositoryDirectory, this.projectName));
    }

    private void openLitematicaMenu()
    {
        GuiBase.openGui(new GuiMainMenu());
    }

    private boolean updatePlacementOrigin(BlockPos origin)
    {
        if (this.state == null)
        {
            return false;
        }

        try
        {
            boolean overlayUpdated = LvcSemanticProjectEditor.updatePlacementOrigin(this.repositoryDirectory, origin);
            this.refreshState();
            this.clearStatus();
            LvcDiagnostics.debug("GuiLvcProjectEditor: placement origin saved repo='{}' origin='{}' overlayUpdated={}",
                    this.repositoryDirectory, origin, overlayUpdated);
            return true;
        }
        catch (Exception e)
        {
            this.setErrorStatus(e.getMessage());
            return false;
        }
    }

    private void setOriginToPlayer()
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;

        if (player == null)
        {
            this.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.no_player");
            return;
        }

        if (world == null)
        {
            this.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return;
        }

        try
        {
            BlockPos origin = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(player);
            boolean overlayUpdated = LvcSemanticProjectEditor.updatePlacementOrigin(this.repositoryDirectory, origin);
            this.clearStatus();
            this.initGui();
            LvcDiagnostics.debug("GuiLvcProjectEditor: placement origin moved to player repo='{}' origin='{}' overlayUpdated={}",
                    this.repositoryDirectory, origin, overlayUpdated);
        }
        catch (Exception e)
        {
            this.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project_editor.save_failed", e.getMessage());
        }
    }

    private void setSavedStatus(String key)
    {
        this.statusText = StringUtils.translate(key);
        this.statusColor = SUCCESS_COLOR;
    }

    private void clearStatus()
    {
        this.statusText = "";
        this.statusColor = STATUS_COLOR;
    }

    private void setErrorStatus(@Nullable String message)
    {
        this.statusText = message == null || message.isBlank() ? StringUtils.translate("gitmatica.error.lvc_project_editor.unknown_error") : message;
        this.statusColor = ERROR_COLOR;
    }

    private int getBottomButtonY()
    {
        return this.getScreenHeight() - 24;
    }

    private int getTopControlsMaxX()
    {
        return this.getScreenWidth() - MARGIN;
    }

    private boolean worldOriginFitsProjectRow()
    {
        return this.getLeftControlsRightForOrigin(this.getProjectNameFieldBaseWidth()) + COORDINATE_GROUP_LEFT_GAP + COORDINATE_GROUP_WIDTH + MARGIN <= this.getScreenWidth();
    }

    private int getCoordinateGroupY()
    {
        if (this.worldOriginFitsProjectRow())
        {
            return COORDINATE_TOP_Y;
        }

        return this.getActionButtonY() + BUTTON_HEIGHT + 8;
    }

    private int getOriginGroupX()
    {
        if (!this.worldOriginFitsProjectRow())
        {
            return FORM_X;
        }

        return this.getLeftControlsRightForOrigin(this.getProjectNameFieldWidth()) + COORDINATE_GROUP_LEFT_GAP;
    }

    private int getCoordinateGroupsBottomY()
    {
        return this.getCoordinateGroupY() + WidgetLvcBlockPosEditor.DEFAULT_HEIGHT;
    }

    private int getSubRegionLabelY()
    {
        return Math.max(this.getActionButtonY() + BUTTON_HEIGHT + 8, this.getCoordinateGroupsBottomY() + 12);
    }

    private int getSubRegionListY()
    {
        return this.getSubRegionLabelY() + 9;
    }

    private int getBottomNavigationButtonWidth(ButtonType type)
    {
        return this.getStringWidth(type.getDisplayName()) + 20;
    }

    private int getBottomNavigationWidth()
    {
        return this.getBottomNavigationButtonWidth(ButtonType.PROJECT_MANAGER) + BUTTON_GROUP_GAP +
                this.getBottomNavigationButtonWidth(ButtonType.LITEMATICA_MENU);
    }

    private int getProjectNameFieldWidth()
    {
        int maxWidth = this.getProjectNameFieldBaseWidth();

        if (!this.worldOriginFitsProjectRow())
        {
            return maxWidth;
        }

        int availableRight = this.getScreenWidth() - MARGIN - COORDINATE_GROUP_LEFT_GAP - COORDINATE_GROUP_WIDTH;
        return Math.max(140, Math.min(maxWidth, availableRight - FORM_X));
    }

    private int getProjectNameFieldBaseWidth()
    {
        int targetWidth = this.getProjectNameTargetRightX() - FORM_X;
        int availableWidth = this.getScreenWidth() - FORM_X - MARGIN;
        return Math.min(Math.min(PROJECT_NAME_FIELD_WIDTH, Math.max(80, targetWidth)), Math.max(80, availableWidth));
    }

    private int getProjectNameTargetRightX()
    {
        return TOP_BUTTON_X +
                this.getButtonWidth(ButtonType.NEW_SUB_REGION) +
                BUTTON_GROUP_GAP +
                this.getButtonWidth(ButtonType.SAVE_VERSION) +
                BUTTON_GROUP_GAP +
                this.getButtonWidth(ButtonType.MANUAL_ORIGIN) / 2;
    }

    private int getLeftControlsRightForOrigin(int projectNameFieldWidth)
    {
        int right = FORM_X + projectNameFieldWidth;
        right = Math.max(right, TOP_BUTTON_X + this.getActionButtonGroupWidth());
        right = Math.max(right, TOP_BUTTON_X + this.getSelectionButtonGroupWidth());

        return right;
    }

    private int getActionButtonGroupWidth()
    {
        return this.getButtonWidth(ButtonType.NEW_SUB_REGION) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.SAVE_VERSION) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.MANUAL_ORIGIN);
    }

    private int getSelectionButtonGroupWidth()
    {
        return this.getButtonWidth(ButtonType.CHANGE_SELECTION_MODE) + BUTTON_GROUP_GAP + this.getButtonWidth(ButtonType.CHANGE_CORNER_MODE);
    }

    private int getButtonWidth(ButtonType type)
    {
        return this.getStringWidth(type.getDisplayName()) + 10;
    }

    private int getProjectNameLabelY()
    {
        return TOP_Y + BUTTON_HEIGHT + PROJECT_NAME_TOP_GAP;
    }

    private int getProjectNameFieldY()
    {
        return this.getProjectNameLabelY() + 13;
    }

    private int getActionButtonY()
    {
        return this.getProjectNameFieldY() + TEXT_FIELD_HEIGHT + ACTION_BUTTON_TOP_GAP;
    }

    @Nullable
    public String getSelectedRegionId()
    {
        return this.selectedRegionId;
    }

    @Override
    public void onSelectionChange(LvcManifest.Region entry)
    {
        this.selectedRegionId = entry.id();
    }

    private enum ButtonType
    {
        CHANGE_SELECTION_MODE("litematica.gui.button.area_editor.change_selection_mode"),
        CHANGE_CORNER_MODE("litematica.gui.button.area_editor.change_corner_mode"),
        NEW_SUB_REGION("gitmatica.gui.button.lvc_project_editor.new_sub_region"),
        SAVE_VERSION("gitmatica.gui.button.lvc_project.save_version"),
        MANUAL_ORIGIN("gitmatica.gui.button.lvc_project_editor.manual_origin"),
        SET_ORIGIN_TO_PLAYER("litematica.gui.button.move_to_player"),
        ANALYZE_AREA("litematica.gui.button.area_editor.analyze_area"),
        PROJECT_MANAGER("gitmatica.gui.button.lvc_project.back_to_manager"),
        LITEMATICA_MENU("gitmatica.gui.button.lvc_project.litematica_menu");

        private final String translationKey;

        ButtonType(String translationKey)
        {
            this.translationKey = translationKey;
        }

        private String getDisplayName()
        {
            if (this == CHANGE_SELECTION_MODE)
            {
                return StringUtils.translate(this.translationKey, SelectionMode.NORMAL.getDisplayName());
            }

            if (this == CHANGE_CORNER_MODE)
            {
                return StringUtils.translate(this.translationKey, Configs.Generic.SELECTION_CORNERS_MODE.getOptionListValue().getDisplayName());
            }

            if (this == MANUAL_ORIGIN)
            {
                return StringUtils.translate(this.translationKey, StringUtils.translate("malilib.gui.label_colored.off"));
            }

            return StringUtils.translate(this.translationKey);
        }

        private boolean isEnabled()
        {
            return this != CHANGE_SELECTION_MODE &&
                    this != CHANGE_CORNER_MODE &&
                    this != NEW_SUB_REGION &&
                    this != SAVE_VERSION &&
                    this != ANALYZE_AREA;
        }
    }

    private record ButtonListener(GuiLvcProjectEditor gui, ButtonType type) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case SET_ORIGIN_TO_PLAYER -> this.gui.setOriginToPlayer();
                case MANUAL_ORIGIN -> this.gui.setSavedStatus("gitmatica.message.lvc_project_editor.metadata_locked");
                case PROJECT_MANAGER -> this.gui.openProjectManager();
                case LITEMATICA_MENU -> this.gui.openLitematicaMenu();
                default ->
                {
                }
            }
        }
    }

}
