package me.niicide.lvc.gui;

import me.niicide.lvc.git.LvcGitHistoryOps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import me.niicide.lvc.task.LvcOperationJournal;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

class GuiLvcRecoveryDialog extends GuiLvcPanelDialog
{
    private static final int DIALOG_WIDTH = 360;
    private static final int MIN_DIALOG_HEIGHT = 132;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_MIN_WIDTH = 40;
    private static final int BUTTON_TEXT_PADDING = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int METADATA_LABEL_COLOR = 0xFFAAAAAA;

    private final LvcOperationJournal.Entry entry;
    private final LvcOperationJournal.Operation operation;
    private final Runnable restartAction;
    private final Runnable abortAction;
    private final Runnable cancelAction;
    private List<RecoveryLine> lines = List.of();
    private boolean handled;

    GuiLvcRecoveryDialog(Screen parent, LvcOperationJournal.Entry entry, LvcOperationJournal.Operation operation,
                         Runnable restartAction, Runnable abortAction, Runnable cancelAction)
    {
        super(parent, "gitmatica.gui.title.lvc_project.recovery_required",
                DIALOG_WIDTH, MIN_DIALOG_HEIGHT, PADDING);
        this.entry = entry;
        this.operation = operation;
        this.restartAction = restartAction;
        this.abortAction = abortAction;
        this.cancelAction = cancelAction;
    }

    @Override
    public void initGui()
    {
        this.clearElements();
        this.lines = this.createLines();
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

        for (RecoveryLine line : this.lines)
        {
            int x = this.dialogLeft + PADDING;
            int labelColor = line.value() == null ? COLOR_WHITE : METADATA_LABEL_COLOR;
            this.drawStringWithShadow(ctx, line.label(), x, y, labelColor);

            if (line.value() != null)
            {
                this.drawStringWithShadow(ctx, line.value(), x + this.getStringWidth(line.label()), y, COLOR_WHITE);
            }

            y += LINE_HEIGHT;
        }
    }

    private List<RecoveryLine> createLines()
    {
        List<RecoveryLine> result = new ArrayList<>();
        int maxWidth = DIALOG_WIDTH - PADDING * 2;
        this.addWrappedLine(result, StringUtils.translate("gitmatica.gui.message.lvc_project.recovery_required"), maxWidth);

        result.add(RecoveryLine.metadata(
                StringUtils.translate("gitmatica.gui.label.lvc_project.recovery_name"),
                displayOperation(this.operation)
        ));

        if (this.entry.targetCommit() != null && !this.entry.targetCommit().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("gitmatica.gui.label.lvc_project.recovery_commit"),
                    shortCommit(this.entry.targetCommit())
            ));
        }

        if (this.entry.targetBranch() != null && !this.entry.targetBranch().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("gitmatica.gui.label.lvc_project.recovery_branch"),
                    this.entry.targetBranch()
            ));
        }

        if (this.entry.startedAt() != null && !this.entry.startedAt().isBlank())
        {
            result.add(RecoveryLine.metadata(
                    StringUtils.translate("gitmatica.gui.label.lvc_project.recovery_started_at"),
                    formatStartedAt(this.entry.startedAt())
            ));
        }

        return result;
    }

    private void addWrappedLine(List<RecoveryLine> lines, String text, int maxWidth)
    {
        for (String line : LvcGuiText.wrapTextToWidth(text, maxWidth, this::getStringWidth))
        {
            lines.add(RecoveryLine.text(line));
        }
    }

    private void createButtons()
    {
        String restartLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.restart_operation");
        String abortLabel = StringUtils.translate("gitmatica.gui.button.lvc_project.abort_operation");
        String cancelLabel = StringUtils.translate("malilib.gui.button.cancel");
        int y = this.dialogTop + this.dialogHeight - PADDING - BUTTON_HEIGHT;
        int x = this.dialogLeft + PADDING;
        LvcButtonRowLayout buttons = new LvcButtonRowLayout(
                x, y, BUTTON_HEIGHT, BUTTON_GAP, BUTTON_MIN_WIDTH, BUTTON_TEXT_PADDING,
                this::getStringWidth);

        this.addButton(buttons.next(restartLabel),
                (button, mouseButton) -> this.chooseAndClose(this.restartAction));
        this.addButton(buttons.next(abortLabel),
                (button, mouseButton) -> this.chooseAndClose(this.abortAction));
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

    static String displayOperation(LvcOperationJournal.Operation operation)
    {
        return switch (operation)
        {
            case INIT -> StringUtils.translate("gitmatica.gui.button.lvc_project.create_short");
            case SAVE -> StringUtils.translate("gitmatica.gui.button.lvc_project.save_version");
            case UPDATE_AREAS -> StringUtils.translate("gitmatica.gui.button.lvc_project.update_areas");
            case CHECKOUT -> StringUtils.translate("gitmatica.gui.button.lvc_project.checkout_version");
            case DISCARD -> StringUtils.translate("gitmatica.gui.button.lvc_project.discard_changes");
            case CLEAR -> StringUtils.translate("gitmatica.gui.button.lvc_project.clear_area");
            case MERGE -> StringUtils.translate("gitmatica.gui.button.lvc_project.branch_action_merge");
            case DELETE_VERSION -> StringUtils.translate("gitmatica.gui.label.lvc_project.delete_latest_version");
        };
    }

    private static String shortCommit(String commit)
    {
        return commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    private static String formatStartedAt(String startedAt)
    {
        try
        {
            return LvcGitHistoryOps.formatCommitTime(Instant.parse(startedAt));
        }
        catch (Exception e)
        {
            return startedAt;
        }
    }

    private record RecoveryLine(String label, String value)
    {
        private static RecoveryLine text(String text)
        {
            return new RecoveryLine(text, null);
        }

        private static RecoveryLine metadata(String label, String value)
        {
            return new RecoveryLine(label, value);
        }
    }
}
