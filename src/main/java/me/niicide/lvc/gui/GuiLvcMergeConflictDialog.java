package me.niicide.lvc.gui;

import me.niicide.lvc.git.LvcBranchMergeConflictResolution;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcMergeConflictDialog extends GuiLvcPanelDialog
{
    private static final String TITLE_KEY = "gitmatica.gui.title.lvc_project.merge_conflict";
    private static final String MESSAGE_KEY = "gitmatica.gui.message.lvc_project.merge_conflict_choose";
    private static final int DIALOG_WIDTH = 300;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int PADDING = 10;
    private static final int MESSAGE_Y = 30;
    private static final int BUTTON_TOP_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 44;
    private static final int BUTTON_TEXT_PADDING = 14;
    private static final int MESSAGE_COLOR = 0xFFAAAAAA;
    private static final int LINE_HEIGHT = 12;

    private final Consumer<LvcBranchMergeConflictResolution> resolutionConsumer;
    private final Runnable cancelAction;
    private List<String> messageLines = List.of();
    private boolean handled;

    GuiLvcMergeConflictDialog(Screen parent,
                              Consumer<LvcBranchMergeConflictResolution> resolutionConsumer,
                              Runnable cancelAction)
    {
        super(parent, TITLE_KEY, DIALOG_WIDTH, MIN_DIALOG_HEIGHT, PADDING);
        this.resolutionConsumer = resolutionConsumer;
        this.cancelAction = cancelAction;
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.messageLines = LvcGuiText.wrapTextToWidth(
                StringUtils.translate(MESSAGE_KEY),
                DIALOG_WIDTH - PADDING * 2,
                this::getStringWidth
        );
        int messageHeight = Math.max(LINE_HEIGHT, this.messageLines.size() * LINE_HEIGHT);
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, MESSAGE_Y + messageHeight + BUTTON_TOP_GAP + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();
        this.createButtons();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.cancelAndClose();
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    protected void drawPanelContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        this.drawMessageLines(ctx, this.dialogTop + MESSAGE_Y);
    }

    private void drawMessageLines(GuiContext ctx, int y)
    {
        int x = this.dialogLeft + PADDING;

        for (String line : this.messageLines)
        {
            this.drawStringWithShadow(ctx, line, x, y, MESSAGE_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private void createButtons()
    {
        String baseLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.merge_accept_base");
        String incomingLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.merge_accept_incoming");
        String yoursLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.merge_accept_yours");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int x = this.dialogLeft + PADDING;
        LvcButtonRowLayout buttons = new LvcButtonRowLayout(
                x, y, BUTTON_HEIGHT, BUTTON_GAP, BUTTON_MIN_WIDTH, BUTTON_TEXT_PADDING,
                this::getStringWidth);

        this.addButton(buttons.next(baseLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcBranchMergeConflictResolution.BASE));
        this.addButton(buttons.next(incomingLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcBranchMergeConflictResolution.INCOMING));
        this.addButton(buttons.next(yoursLabel),
                (button, mouseButton) -> this.chooseAndClose(LvcBranchMergeConflictResolution.YOURS));
        this.addButton(buttons.next(cancelLabel), (button, mouseButton) -> this.cancelAndClose());
    }

    private void chooseAndClose(LvcBranchMergeConflictResolution resolution)
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        this.resolutionConsumer.accept(resolution);
    }

    private void cancelAndClose()
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        this.cancelAction.run();
    }
}
