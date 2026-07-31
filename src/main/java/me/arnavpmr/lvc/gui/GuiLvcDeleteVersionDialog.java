package me.arnavpmr.lvc.gui;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

final class GuiLvcDeleteVersionDialog extends GuiLvcPanelDialog
{
    private static final int DIALOG_WIDTH = 340;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 48;
    private static final int BUTTON_TEXT_PADDING = 12;
    private static final int LINE_HEIGHT = 12;
    private static final int MESSAGE_COLOR = 0xFFAAAAAA;

    private final Runnable keepChangesAction;
    private final Runnable deleteChangesAction;
    private final Runnable cancelAction;
    private List<String> lines = List.of();
    private boolean handled;

    GuiLvcDeleteVersionDialog(Screen parent, Runnable keepChangesAction, Runnable deleteChangesAction, Runnable cancelAction)
    {
        super(parent, "gitmatica.gui.title.lvc_project.delete_version", DIALOG_WIDTH, MIN_DIALOG_HEIGHT, PADDING);
        this.keepChangesAction = keepChangesAction;
        this.deleteChangesAction = deleteChangesAction;
        this.cancelAction = cancelAction;
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = LvcGuiText.wrapTextToWidth(
                StringUtils.translate("gitmatica.gui.message.lvc_project.delete_version_note"),
                DIALOG_WIDTH - PADDING * 2,
                this::getStringWidth
        );
        int textHeight = this.lines.size() * LINE_HEIGHT;
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, PADDING + 16 + textHeight + 14 + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();
        this.createButtons();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.chooseAndClose(this.cancelAction);
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    protected void drawPanelContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        int y = this.dialogTop + 28;

        for (String line : this.lines)
        {
            this.drawStringWithShadow(ctx, line, this.dialogLeft + PADDING, y, MESSAGE_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private void createButtons()
    {
        String keepLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.keep_changes");
        String deleteLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.delete_changes");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int x = this.dialogLeft + PADDING;
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        LvcButtonRowLayout buttons = new LvcButtonRowLayout(
                x, y, BUTTON_HEIGHT, BUTTON_GAP, BUTTON_MIN_WIDTH, BUTTON_TEXT_PADDING,
                this::getStringWidth);

        this.addButton(buttons.next(keepLabel),
                (button, mouseButton) -> this.chooseAndClose(this.keepChangesAction));
        this.addButton(buttons.next(deleteLabel),
                (button, mouseButton) -> this.chooseAndClose(this.deleteChangesAction));
        this.addButton(buttons.next(cancelLabel),
                (button, mouseButton) -> this.chooseAndClose(this.cancelAction));
    }

    private void chooseAndClose(Runnable action)
    {
        if (this.handled)
        {
            return;
        }

        this.handled = true;
        this.closeGui(true);
        action.run();
    }
}
