package me.niicide.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import me.niicide.lvc.LvcDiagnostics;

final class GuiLvcWarningConfirmDialog extends GuiDialogBase
{
    private static final int DIALOG_WIDTH = 330;
    private static final int MIN_DIALOG_HEIGHT = 126;
    private static final int PADDING = 10;
    private static final int TITLE_Y = 6;
    private static final int MESSAGE_Y = 27;
    private static final int LINE_HEIGHT = 12;
    private static final int CHECKBOX_GAP = 7;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 44;
    private static final int BUTTON_TEXT_PADDING = 14;
    private static final int BACKGROUND_MOUSE = Integer.MIN_VALUE;
    private static final int WARNING_COLOR = 0xFFFF5555;
    private static final int MESSAGE_COLOR = 0xFFAAAAAA;

    private final ConfigBoolean showAgainConfig;
    private final Runnable confirmedAction;
    private final Runnable cancelAction;
    private final String operationName;
    private final String warningPrefix;
    private final String message;
    private List<String> lines = List.of();
    private WidgetCheckBox dontShowAgainCheckbox;
    private boolean handled;

    GuiLvcWarningConfirmDialog(Screen parent, String titleKey, String messageKey, ConfigBoolean showAgainConfig,
                               String operationName, Runnable confirmedAction, Runnable cancelAction)
    {
        this.showAgainConfig = showAgainConfig;
        this.confirmedAction = confirmedAction;
        this.cancelAction = cancelAction;
        this.operationName = operationName;
        this.warningPrefix = StringUtils.translate("litematica.gui.label.lvc_project.warning_prefix");
        this.message = StringUtils.translate(messageKey);
        this.setParent(parent);
        this.title = StringUtils.translate(titleKey);
        this.useTitleHierarchy = false;
        this.setWidthAndHeight(DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
    }

    @Override
    public void initGui()
    {
        this.clearElements();

        int fullWidth = DIALOG_WIDTH - PADDING * 2;
        int firstWidth = fullWidth - this.getStringWidth(this.warningPrefix) - 4;
        this.lines = this.wrapTextToWidth(this.message, firstWidth, fullWidth);

        int messageHeight = this.lines.size() * LINE_HEIGHT;
        int dialogHeight = Math.max(MIN_DIALOG_HEIGHT,
                MESSAGE_Y + messageHeight + CHECKBOX_GAP + 12 + CHECKBOX_GAP + BUTTON_HEIGHT + PADDING);
        this.setWidthAndHeight(DIALOG_WIDTH, dialogHeight);
        this.centerOnScreen();

        this.createCheckbox(MESSAGE_Y + messageHeight + CHECKBOX_GAP);
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
    protected void drawWidgets(GuiContext ctx, int mouseX, int mouseY)
    {
    }

    @Override
    protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        // This dialog draws its title inside the popup box.
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        Screen parent = this.getParent();

        if (parent != null)
        {
            parent.extractRenderState(ctx.getGuiGraphics(), BACKGROUND_MOUSE, BACKGROUND_MOUSE, partialTicks);
        }

        RenderUtils.drawOutlinedBox(ctx, this.dialogLeft, this.dialogTop, this.dialogWidth, this.dialogHeight,
                0xE0000000, COLOR_HORIZONTAL_BAR);
        this.drawStringWithShadow(ctx, this.getTitleString(), this.dialogLeft + PADDING, this.dialogTop + TITLE_Y, COLOR_WHITE);
        this.drawWarningText(ctx);

        super.drawWidgets(ctx, mouseX, mouseY);
        super.drawButtons(ctx, mouseX, mouseY, partialTicks);
    }

    private void drawWarningText(GuiContext ctx)
    {
        int x = this.dialogLeft + PADDING;
        int y = this.dialogTop + MESSAGE_Y;

        this.drawStringWithShadow(ctx, this.warningPrefix, x, y, WARNING_COLOR);
        x += this.getStringWidth(this.warningPrefix) + 4;

        for (int i = 0; i < this.lines.size(); i++)
        {
            if (i == 1)
            {
                x = this.dialogLeft + PADDING;
            }

            this.drawStringWithShadow(ctx, this.lines.get(i), x, y, MESSAGE_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private void createCheckbox(int yOffset)
    {
        String label = StringUtils.translate("litematica.gui.label.lvc_project.dont_show_warning_again");
        this.dontShowAgainCheckbox = new WidgetCheckBox(
                this.dialogLeft + PADDING,
                this.dialogTop + yOffset,
                Icons.CHECKBOX_UNSELECTED,
                Icons.CHECKBOX_SELECTED,
                label
        );
        this.addWidget(this.dontShowAgainCheckbox);
    }

    private void createButtons()
    {
        String okLabel = StringUtils.translate("malilib.gui.button.ok");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int okWidth = this.buttonWidth(okLabel);
        int cancelWidth = this.buttonWidth(cancelLabel);
        int x = this.dialogLeft + PADDING;
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;

        this.addButton(new ButtonGeneric(x, y, okWidth, BUTTON_HEIGHT, okLabel),
                (button, mouseButton) -> this.confirmAndClose());
        x += okWidth + BUTTON_GAP;
        this.addButton(new ButtonGeneric(x, y, cancelWidth, BUTTON_HEIGHT, cancelLabel),
                (button, mouseButton) -> this.chooseAndClose(this.cancelAction));
    }

    private int buttonWidth(String label)
    {
        return Math.max(BUTTON_MIN_WIDTH, this.getStringWidth(label) + BUTTON_TEXT_PADDING);
    }

    private void confirmAndClose()
    {
        if (this.dontShowAgainCheckbox != null && this.dontShowAgainCheckbox.isChecked())
        {
            this.showAgainConfig.setBooleanValue(false);
            Configs.saveToFile();
            LvcDiagnostics.info("{} warning disabled by global config", this.operationName);
        }

        this.chooseAndClose(this.confirmedAction);
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

    private List<String> wrapTextToWidth(String text, int firstLineWidth, int fullWidth)
    {
        List<String> wrapped = new ArrayList<>();

        for (String paragraph : text.split("\\R", -1))
        {
            this.wrapParagraphToWidth(paragraph, firstLineWidth, fullWidth, wrapped);
        }

        return wrapped.isEmpty() ? List.of("") : wrapped;
    }

    private void wrapParagraphToWidth(String paragraph, int firstLineWidth, int fullWidth, List<String> wrapped)
    {
        if (paragraph.isBlank())
        {
            wrapped.add("");
            return;
        }

        String line = "";

        for (String word : paragraph.trim().split("\\s+"))
        {
            int maxWidth = wrapped.isEmpty() ? firstLineWidth : fullWidth;
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (this.getStringWidth(candidate) <= maxWidth)
            {
                line = candidate;
                continue;
            }

            if (!line.isEmpty())
            {
                wrapped.add(line);
                line = "";
                maxWidth = fullWidth;
            }

            if (this.getStringWidth(word) <= maxWidth)
            {
                line = word;
            }
            else
            {
                line = this.wrapLongWordToWidth(word, maxWidth, wrapped);
            }
        }

        if (!line.isEmpty())
        {
            wrapped.add(line);
        }
    }

    private String wrapLongWordToWidth(String word, int maxWidth, List<String> wrapped)
    {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < word.length(); i++)
        {
            String candidate = builder.toString() + word.charAt(i);

            if (this.getStringWidth(candidate) > maxWidth && !builder.isEmpty())
            {
                wrapped.add(builder.toString());
                builder.setLength(0);
            }

            builder.append(word.charAt(i));
        }

        return builder.toString();
    }
}
