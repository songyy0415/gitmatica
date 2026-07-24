package me.niicide.lvc.gui;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcOperationCoordinator
{
    private static final int UNSAVED_CHANGES_TEXT_COLOR = 0xFFB0B0B0;
    private static final int UNSAVED_CHANGES_DIALOG_WIDTH = 250;
    private static final int UNSAVED_CHANGES_DIALOG_PADDING = 8;

    private LvcOperationCoordinator()
    {
    }

    static Optional<LvcOperationHandle> acquire(GuiLvcProjectController controller, String name)
    {
        Path repositoryDirectory = controller.gui.repositoryDirectory;
        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire(name, repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
        }
        else
        {
            controller.focusTrackingOverlay();
            LvcDiagnostics.debug("LVC operation acquired name='{}' repo='{}'", name, repositoryDirectory);
        }

        return handle;
    }

    static void scheduleStarted(Level world, ITask task, String taskName)
    {
        LvcTaskScheduling.scheduleForWorld(world, task);
        LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_started", taskName);
    }

    static String regionCountText(int regionCount)
    {
        return StringUtils.translate(regionCount == 1
                ? "gitmatica.gui.label.lvc_project.region_count_one"
                : "gitmatica.gui.label.lvc_project.region_count_many",
                regionCount);
    }

    static Screen confirmParent(GuiLvcProjectController controller)
    {
        Screen currentScreen = GuiUtils.getCurrentScreen();
        Screen parent = currentScreen == controller.gui ? controller.gui : currentScreen;
        LvcDiagnostics.debug("LVC confirm parent selected current={} parent={} projectOpen={}",
                screenName(currentScreen), screenName(parent), currentScreen == controller.gui);
        return parent;
    }

    static void showUnsavedChangesNotice(GuiLvcProjectController controller, String operationName)
    {
        LvcDiagnostics.debug("LVC world mutation blocked by unsaved changes operation='{}' repo='{}'",
                operationName, controller.gui.repositoryDirectory);
        GuiBase.openGui(new GuiLvcNoticeDialog(
                confirmParent(controller),
                UNSAVED_CHANGES_DIALOG_WIDTH,
                UNSAVED_CHANGES_DIALOG_PADDING,
                "gitmatica.gui.title.lvc_project.unsaved_changes",
                "gitmatica.gui.message.lvc_project.unsaved_changes",
                UNSAVED_CHANGES_TEXT_COLOR
        ));
    }

    private static String screenName(Screen screen)
    {
        return screen == null ? "<none>" : screen.getClass().getSimpleName();
    }

    record ConfirmListener(Runnable confirmed) implements IConfirmationListener
    {
        @Override
        public boolean onActionConfirmed()
        {
            this.confirmed.run();
            return true;
        }

        @Override
        public boolean onActionCancelled()
        {
            return true;
        }
    }
}
