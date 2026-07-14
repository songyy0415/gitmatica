package me.niicide.lvc.gui;

import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.LvcPlayerIdentity;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.git.LvcMergeConflictException;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcOperationJournal;
import me.niicide.lvc.task.LvcRemoteServerApplyTask;
import me.niicide.lvc.task.LvcSemanticDiscardTask;
import me.niicide.lvc.task.LvcSemanticScanTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

final class LvcMergeWorkflow
{
    private LvcMergeWorkflow()
    {
    }

    static void mergeBranch(GuiLvcProjectController controller, String sourceBranch)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        if (sourceBranch == null || sourceBranch.isBlank())
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_required");
            return;
        }

        if (controller.gui.detachedHead)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_detached");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;

        if (player == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_player");
            return;
        }

        if (world == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Merge Branch");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            Level scanWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            Level restoreWorld = LvcWorldBackend.resolve(world) == LvcWorldBackend.DIRECT ?
                    LvcWorldAccess.resolveSemanticRestoreWorld(world) : world;
            LvcSemanticScanTask task = new LvcSemanticScanTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    scanWorld,
                    LvcTaskCallbacks.of(
                            scan -> handleMergePreflight(controller, handle.get(), restoreWorld,
                                    sourceBranch.trim(), player, scan),
                            e -> LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH,
                                    "litematica.error.lvc_project.merge_branch_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO,
                                    "litematica.message.lvc_project.task_aborted", "LVC Merge Branch")
                    ),
                    false
            );
            LvcOperationCoordinator.scheduleStarted(scanWorld, task, "LVC Merge Branch");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
        }
    }

    private static void handleMergePreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, String sourceBranch, Player player,
                                             LvcProjectService.SemanticScanResult scan)
    {
        try
        {
            if (scan.unknownChunks() > 0)
            {
                LvcTaskRegistry.release(handle);
                LvcGuiMessages.showUnloadedTrackedChunks(Operation.MERGE_BRANCH,
                        "litematica.error.lvc_project.merge_branch_failed", scan.unknownChunks());
                return;
            }

            boolean gitDirty = LvcProjectService.hasUncommittedChanges(controller.gui.repositoryDirectory);

            if (!scan.clean() || gitDirty)
            {
                LvcTaskRegistry.release(handle);
                LvcDiagnostics.debug("LVC Merge Branch preflight dirty repo='{}' changedChunks={} addedChunks={} removedChunks={} gitDirty={}",
                        controller.gui.repositoryDirectory, scan.changedChunks(), scan.addedChunks(), scan.removedChunks(),
                        gitDirty);
                LvcOperationCoordinator.showUnsavedChangesNotice(controller, "LVC Merge Branch");
                return;
            }

            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            validateRemoteMergeApplyReady(restoreWorld);
            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                    controller.gui.repositoryDirectory, sourceBranch, identity);

            if (result.status() == LvcProjectService.BranchMergeStatus.UP_TO_DATE)
            {
                LvcTaskRegistry.release(handle);
                controller.focusTrackingOverlay();
                controller.refreshRepositoryState();
                controller.gui.refreshHistory();
                controller.gui.initGui();
                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.merge_branch_up_to_date",
                        result.sourceBranch(), result.targetBranch());
                return;
            }

            scheduleMergeRestore(controller, handle, restoreWorld, result);
        }
        catch (LvcMergeConflictException e)
        {
            openMergeConflictDialog(controller, handle, restoreWorld, sourceBranch, player, e);
            LvcDiagnostics.debug("LVC merge conflict detected repo='{}' source='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, e.getMessage());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
            LvcDiagnostics.debug("LVC merge failed repo='{}' source='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, e.getMessage());
        }
    }

    private static void openMergeConflictDialog(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                Level restoreWorld, String sourceBranch, Player player,
                                                LvcMergeConflictException conflict)
    {
        LvcDiagnostics.debug("LVC merge conflict requires user choice repo='{}' source='{}' conflict='{}'",
                controller.gui.repositoryDirectory, sourceBranch, conflict.getMessage());
        GuiBase.openGui(new GuiLvcMergeConflictDialog(
                LvcOperationCoordinator.confirmParent(controller),
                resolution -> resolveMergeConflict(controller, handle, restoreWorld, sourceBranch, player, resolution),
                () ->
                {
                    LvcTaskRegistry.release(handle);
                    LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_conflicts");
                }
        ));
    }

    private static void resolveMergeConflict(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, String sourceBranch, Player player,
                                             LvcProjectService.BranchMergeConflictResolution resolution)
    {
        try
        {
            if (LvcProjectService.hasUncommittedChanges(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle);
                LvcOperationCoordinator.showUnsavedChangesNotice(controller, "LVC Merge Branch");
                return;
            }

            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            validateRemoteMergeApplyReady(restoreWorld);
            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                    controller.gui.repositoryDirectory, sourceBranch, identity, resolution);

            if (result.status() == LvcProjectService.BranchMergeStatus.UP_TO_DATE)
            {
                LvcTaskRegistry.release(handle);
                controller.focusTrackingOverlay();
                controller.refreshRepositoryState();
                controller.gui.refreshHistory();
                controller.gui.initGui();
                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.merge_branch_up_to_date",
                        result.sourceBranch(), result.targetBranch());
                return;
            }

            LvcDiagnostics.debug("LVC merge conflict resolved repo='{}' source='{}' resolution='{}' commit='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, result.commitId());
            scheduleMergeRestore(controller, handle, restoreWorld, result);
        }
        catch (LvcMergeConflictException e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.merge_branch_conflicts");
            LvcDiagnostics.debug("LVC merge conflict resolution failed repo='{}' source='{}' resolution='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, e.getMessage());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
            LvcDiagnostics.debug("LVC merge conflict resolution failed repo='{}' source='{}' resolution='{}' error='{}'",
                    controller.gui.repositoryDirectory, sourceBranch, resolution, e.getMessage());
        }
    }

    private static void scheduleMergeRestore(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, LvcProjectService.BranchMergeResult merge) throws IOException
    {
        if (LvcWorldBackend.resolve(restoreWorld) != LvcWorldBackend.DIRECT)
        {
            scheduleRemoteMergeRestore(controller, handle, restoreWorld, merge);
            return;
        }

        controller.removeTrackingOverlay();
        LvcSemanticDiscardTask task = new LvcSemanticDiscardTask(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                merge.commitId(),
                LvcTaskCallbacks.of(
                        result ->
                        {
                            controller.loadTrackingOverlay();
                            controller.refreshRepositoryState();
                            controller.gui.focusHistoryCommitAfterNextRefresh(merge.commitId());
                            controller.gui.refreshHistory();
                            controller.gui.initGui();
                            LvcGuiMessages.show(MessageType.SUCCESS,
                                    merge.status() == LvcProjectService.BranchMergeStatus.FAST_FORWARD ?
                                            "litematica.message.lvc_project.merge_branch_fast_forward" :
                                            "litematica.message.lvc_project.merge_branch_merged",
                                    merge.sourceBranch(), merge.targetBranch(),
                                    LvcOperationCoordinator.regionCountText(result.restoredRegionCount()));
                        },
                        e -> LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH,
                                "litematica.error.lvc_project.merge_branch_failed", e, true),
                        () -> LvcGuiMessages.show(MessageType.INFO,
                                "litematica.message.lvc_project.task_aborted", "LVC Merge Branch")
                ),
                "LVC Merge Branch",
                LvcOperationJournal.Operation.MERGE
        );
        LvcTaskScheduling.scheduleForWorld(restoreWorld, task);
    }

    private static void scheduleRemoteMergeRestore(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                   Level restoreWorld, LvcProjectService.BranchMergeResult merge)
    {
        try
        {
            controller.removeTrackingOverlay();
            LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.merge(
                    handle,
                    controller.gui.repositoryDirectory,
                    restoreWorld,
                    merge.commitId(),
                    merge.targetBranch(),
                    merge.sourceBranch(),
                    merge.previousHead(),
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                controller.loadTrackingOverlay();
                                controller.refreshRepositoryState();
                                controller.gui.focusHistoryCommitAfterNextRefresh(merge.commitId());
                                controller.gui.refreshHistory();
                                controller.gui.initGui();
                                LvcGuiMessages.show(MessageType.SUCCESS,
                                        merge.status() == LvcProjectService.BranchMergeStatus.FAST_FORWARD ?
                                                "litematica.message.lvc_project.merge_branch_fast_forward" :
                                                "litematica.message.lvc_project.merge_branch_merged",
                                        merge.sourceBranch(), merge.targetBranch(),
                                        LvcOperationCoordinator.regionCountText(result.regionCount()));
                                showLossyRemoteWarning(result);
                            },
                            e -> LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH,
                                    "litematica.error.lvc_project.merge_branch_failed", e, true),
                            () -> LvcGuiMessages.show(MessageType.INFO,
                                    "litematica.message.lvc_project.task_aborted", "LVC Merge Branch")
                    )
            );
            LvcTaskScheduling.scheduleClient(task);
            LvcDiagnostics.debug("LVC remote merge restore scheduled repo='{}' source='{}' target='{}' commit='{}' previousHead='{}'",
                    controller.gui.repositoryDirectory, merge.sourceBranch(), merge.targetBranch(), merge.commitId(),
                    merge.previousHead());
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            LvcGuiMessages.showTaskError(Operation.MERGE_BRANCH, "litematica.error.lvc_project.merge_branch_failed", e);
        }
    }

    private static void validateRemoteMergeApplyReady(Level restoreWorld) throws Exception
    {
        if (LvcWorldBackend.resolve(restoreWorld) != LvcWorldBackend.DIRECT)
        {
            LvcRemoteServerApplyTask.validateRemoteApplyReady(restoreWorld);
        }
    }

    private static void showLossyRemoteWarning(LvcRemoteServerApplyTask.Result result)
    {
        if (result.lossy())
        {
            LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_apply");
        }
    }

}
