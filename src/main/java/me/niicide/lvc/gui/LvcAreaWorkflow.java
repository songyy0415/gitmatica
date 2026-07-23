package me.niicide.lvc.gui;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcRemoteServerApplyTask;
import me.niicide.lvc.task.LvcSemanticClearTask;
import me.niicide.lvc.task.LvcSemanticDiscardTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

final class LvcAreaWorkflow
{
    private LvcAreaWorkflow()
    {
    }

    static void promptClearArea(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Level world = minecraft.level;

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Clear Area");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            LvcWorldBackend backend = LvcWorldBackend.resolve(world);

            if (backend != LvcWorldBackend.DIRECT)
            {
                LvcDiagnostics.debug("LVC Clear Area using remote backend={} repo='{}'",
                        backend.id(), controller.gui.repositoryDirectory);
                openRemoteClearAreaConfirm(controller, handle.get(), world);
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
            LvcDiagnostics.debug("LVC Clear Area starting without preflight scan repo='{}'",
                    controller.gui.repositoryDirectory);
            openClearAreaConfirm(controller, handle.get(), restoreWorld);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e);
        }
    }

    static void discardChanges(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Level world = minecraft.level;

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        try
        {
            LvcWorldBackend backend = LvcWorldBackend.resolve(world);

            if (backend != LvcWorldBackend.DIRECT)
            {
                openDiscardConfirm(controller, () -> scheduleRemoteDiscard(controller, world));
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(world);
            openDiscardConfirm(controller, () -> scheduleDiscard(controller, restoreWorld));
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.DISCARD_CHANGES, "litematica.error.lvc_project.discard_failed", e);
        }
    }

    private static void scheduleRemoteClear(GuiLvcProjectController controller, LvcOperationHandle handle, Level world)
    {
        LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.clear(
                handle,
                controller.gui.repositoryDirectory,
                world,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();
                            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.remote_clear_sent",
                                    LvcOperationCoordinator.regionCountText(result.regionCount()));
                            showLossyRemoteWarning(result);
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Clear Area")
                )
        );
        LvcTaskScheduling.scheduleClient(task);
    }

    static void scheduleClear(GuiLvcProjectController controller, LvcOperationHandle handle, Level restoreWorld)
    {
        LvcSemanticClearTask task = new LvcSemanticClearTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();
                            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.clear_area_cleared",
                                    result.clearedBlocks(), LvcOperationCoordinator.regionCountText(result.regionCount()));
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.CLEAR_AREA, "litematica.error.lvc_project.clear_area_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Clear Area")
                )
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

    private static void scheduleDiscard(GuiLvcProjectController controller, Level restoreWorld)
    {
        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Discard Changes");

        if (handle.isEmpty())
        {
            return;
        }

        scheduleDiscard(controller, handle.get(), restoreWorld, null);
    }

    private static void scheduleDiscard(GuiLvcProjectController controller, LvcOperationHandle handle,
                                        Level restoreWorld, String targetCommit)
    {
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                targetCommit,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();

                            if (!result.discarded())
                            {
                                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_changes_to_discard");
                                return;
                            }

                            controller.gui.initGui();

                            if (result.restoredRegionCount() > 0)
                            {
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.discarded_changes",
                                        LvcOperationCoordinator.regionCountText(result.restoredRegionCount()));
                            }
                            else
                            {
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.discarded_changes_no_regions");
                            }
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.DISCARD_CHANGES, "litematica.error.lvc_project.discard_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Discard Changes")
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, "LVC Discard Changes");
    }

    private static void scheduleRemoteDiscard(GuiLvcProjectController controller, Level world)
    {
        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Discard Changes");

        if (handle.isEmpty())
        {
            return;
        }

        LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.discard(
                handle.get(),
                controller.gui.repositoryDirectory,
                world,
                null,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.refreshTrackingOverlayAfterWorldMutation();
                            controller.gui.initGui();
                            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.discarded_changes",
                                    LvcOperationCoordinator.regionCountText(result.regionCount()));
                            showLossyRemoteWarning(result);
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.DISCARD_CHANGES, "litematica.error.lvc_project.discard_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Discard Changes")
                )
        );
        LvcTaskScheduling.scheduleClient(task);
    }

    private static void openDiscardConfirm(GuiLvcProjectController controller, Runnable confirmed)
    {
        if (!Configs.Generic.LVC_SHOW_DISCARD_CHANGES_WARNING.getBooleanValue())
        {
            LvcDiagnostics.debug("LVC Discard Changes confirmation skipped by global config repo='{}'",
                    controller.gui.repositoryDirectory);
            confirmed.run();
            return;
        }

        GuiBase.openGui(new GuiLvcWarningConfirmDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.confirm_discard_changes",
                "litematica.gui.message.lvc_project.confirm_discard_changes",
                Configs.Generic.LVC_SHOW_DISCARD_CHANGES_WARNING,
                "LVC Discard Changes",
                confirmed,
                () -> {}
        ));
    }

    private static void openClearAreaConfirm(GuiLvcProjectController controller, LvcOperationHandle handle, Level restoreWorld)
    {
        if (!Configs.Generic.LVC_SHOW_CLEAR_AREA_WARNING.getBooleanValue())
        {
            LvcDiagnostics.debug("LVC Clear Area confirmation skipped by global config repo='{}'",
                    controller.gui.repositoryDirectory);
            scheduleClear(controller, handle, restoreWorld);
            return;
        }

        LvcDiagnostics.debug("LVC Clear Area awaiting confirmation repo='{}'",
                controller.gui.repositoryDirectory);
        GuiBase.openGui(new GuiLvcWarningConfirmDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.confirm_clear_area",
                "litematica.gui.message.lvc_project.confirm_clear_area",
                Configs.Generic.LVC_SHOW_CLEAR_AREA_WARNING,
                "LVC Clear Area",
                () -> scheduleClear(controller, handle, restoreWorld),
                () -> LvcTaskRegistry.release(handle)
        ));
    }

    private static void openRemoteClearAreaConfirm(GuiLvcProjectController controller, LvcOperationHandle handle, Level world)
    {
        if (!Configs.Generic.LVC_SHOW_CLEAR_AREA_WARNING.getBooleanValue())
        {
            LvcDiagnostics.debug("LVC remote Clear Area confirmation skipped by global config repo='{}'",
                    controller.gui.repositoryDirectory);
            scheduleRemoteClear(controller, handle, world);
            return;
        }

        LvcDiagnostics.debug("LVC remote Clear Area awaiting confirmation repo='{}'",
                controller.gui.repositoryDirectory);
        GuiBase.openGui(new GuiLvcWarningConfirmDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.confirm_clear_area",
                "litematica.gui.message.lvc_project.confirm_clear_area",
                Configs.Generic.LVC_SHOW_CLEAR_AREA_WARNING,
                "LVC Clear Area",
                () -> scheduleRemoteClear(controller, handle, world),
                () -> LvcTaskRegistry.release(handle)
        ));
    }

    private static void showLossyRemoteWarning(LvcRemoteServerApplyTask.Result result)
    {
        if (result.lossy())
        {
            LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_apply");
        }
    }
}
