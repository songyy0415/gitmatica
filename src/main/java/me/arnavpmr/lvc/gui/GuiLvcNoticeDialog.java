package me.arnavpmr.lvc.gui;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcNoticeDialog extends GuiLvcPanelDialog
{
    private static final int DEFAULT_DIALOG_WIDTH = 360;
    private static final int MIN_DIALOG_HEIGHT = 92;
    private static final int DEFAULT_PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 12;

    private final String message;
    private final int textColor;
    private final int configuredWidth;
    private final int padding;
    private List<String> lines = List.of();

    GuiLvcNoticeDialog(Screen parent, String titleKey, String messageKey, int textColor, Object... args)
    {
        this(parent, DEFAULT_DIALOG_WIDTH, DEFAULT_PADDING, titleKey, messageKey, textColor, args);
    }

    GuiLvcNoticeDialog(Screen parent, int configuredWidth, int padding, String titleKey, String messageKey, int textColor, Object... args)
    {
        super(parent, titleKey, Math.max(180, configuredWidth), MIN_DIALOG_HEIGHT, Math.max(4, padding));
        this.message = StringUtils.translate(messageKey, args);
        this.textColor = textColor;
        this.configuredWidth = Math.max(180, configuredWidth);
        this.padding = Math.max(4, padding);
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = LvcGuiText.wrapTextToWidth(
                this.message,
                this.configuredWidth - this.padding * 2,
                this::getStringWidth
        );
        int textHeight = this.lines.size() * LINE_HEIGHT;
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT, this.padding + 16 + textHeight + 14 + BUTTON_HEIGHT + this.padding);
        this.setWidthAndHeight(this.configuredWidth, dialogHeight);
        this.centerOnScreen();

        String okLabel = StringUtils.translate("malilib.gui.button.ok");
        int x = this.dialogLeft + this.padding;
        int y = this.dialogTop + this.dialogHeight - this.padding - BUTTON_HEIGHT;
        LvcButtonRowLayout buttons = new LvcButtonRowLayout(
                x, y, BUTTON_HEIGHT, 0, 50, 18, this::getStringWidth);
        this.addButton(buttons.next(okLabel), (button, mouseButton) -> this.closeGui(true));
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.closeGui(true);
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
            this.drawStringWithShadow(ctx, line, this.dialogLeft + this.padding, y, this.textColor);
            y += LINE_HEIGHT;
        }
    }

}
