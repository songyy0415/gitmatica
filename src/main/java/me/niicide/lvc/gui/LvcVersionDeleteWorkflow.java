package me.niicide.lvc.gui;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.git.LvcCommitInfo;
import me.niicide.lvc.git.LvcGitHistoryOps;
import me.niicide.lvc.git.LvcGitUndoOps;
import me.niicide.lvc.git.LvcLatestCommitUndoResult;
import me.niicide.lvc.git.LvcLatestCommitUndoTarget;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcOperationJournal;
import me.niicide.lvc.task.LvcRemoteServerApplyTask;
import me.niicide.lvc.task.LvcSemanticCheckoutTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;

final class LvcVersionDeleteWorkflow
{
    private static final String OPERATION_NAME = "LVC Delete Version";

    private LvcVersionDeleteWorkflow()
    {
    }

    static void prompt(GuiLvcProjectController controller, LvcCommitInfo commit)
    {
        if (controller.promptPendingInterruptedOperationIfNeeded())
        {
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        if (!isLatestDeletableCommit(controller, commit))
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.delete_version_not_latest");
            return;
        }

        LvcDiagnostics.debug("GuiLvcProjectManager: opening delete latest version dialog repo='{}' commit='{}'",
                controller.gui.repositoryDirectory, commit.id());
        controller.gui.blurBranchOverlayInputs();
        GuiBase.openGui(new GuiLvcDeleteVersionDialog(
                LvcOperationCoordinator.confirmParent(controller),
                () -> keepChanges(controller, commit),
                () -> deleteChanges(controller, commit),
                () -> LvcDiagnostics.debug(
                        "GuiLvcProjectManager: delete latest version cancelled repo='{}' commit='{}'",
                        controller.gui.repositoryDirectory, commit.id())
        ));
    }

    static void promptLatest(GuiLvcProjectController controller)
    {
        try
        {
            controller.gui.history = LvcGitHistoryOps.listCommits(controller.gui.repositoryDirectory);

            if (controller.gui.history.isEmpty())
            {
                LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.delete_version_not_latest");
                return;
            }

            prompt(controller, controller.gui.history.get(0));
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.DELETE_VERSION,
                    "gitmatica.error.lvc_project.delete_version_failed", e);
        }
    }

    private static void keepChanges(GuiLvcProjectController controller, LvcCommitInfo commit)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        try
        {
            LvcDiagnostics.debug(
                    "GuiLvcProjectManager: delete latest version keep changes selected repo='{}' commit='{}'",
                    controller.gui.repositoryDirectory, commit.id());
            LvcLatestCommitUndoResult result =
                    LvcGitUndoOps.undoLatestCommitKeepChanges(controller.gui.repositoryDirectory);

            refreshAfterDelete(controller);
            LvcGuiMessages.show(MessageType.SUCCESS,
                    "gitmatica.message.lvc_project.deleted_version_keep_changes", result.shortCommitId());
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.DELETE_VERSION,
                    "gitmatica.error.lvc_project.delete_version_failed", e);
        }
    }

    private static void deleteChanges(GuiLvcProjectController controller, LvcCommitInfo commit)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, OPERATION_NAME);

        if (handle.isEmpty())
        {
            return;
        }

        LvcSemanticCheckoutTask.PreparedCheckout prepared = null;

        try
        {
            LvcDiagnostics.debug(
                    "GuiLvcProjectManager: delete latest version delete changes selected repo='{}' commit='{}'",
                    controller.gui.repositoryDirectory, commit.id());
            Level restoreWorld = minecraft.level;

            if (LvcWorldBackend.resolve(restoreWorld) != LvcWorldBackend.DIRECT)
            {
                scheduleRemoteDelete(controller, handle.get(), restoreWorld, commit);
                return;
            }

            restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(restoreWorld);
            prepared = LvcSemanticCheckoutTask.prepareLatestCommitDelete(
                    handle.get(), controller.gui.repositoryDirectory, restoreWorld);
            String targetBranch = prepared.currentBranchName();

            if (targetBranch == null || targetBranch.isBlank())
            {
                throw new IllegalStateException("LVC repository is not on a local branch");
            }

            controller.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle.get(),
                    prepared,
                    targetBranch,
                    prepared.currentCommitId(),
                    targetBranch,
                    true,
                    LvcOperationJournal.Operation.DELETE_VERSION,
                    OPERATION_NAME,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                refreshAfterDelete(controller);
                                LvcGuiMessages.show(
                                        MessageType.SUCCESS,
                                        "gitmatica.message.lvc_project.deleted_version_delete_changes",
                                        commit.shortId(),
                                        LvcOperationCoordinator.regionCountText(result.regionCount())
                                );
                            },
                            e -> LvcGuiMessages.showTaskError(
                                    Operation.DELETE_VERSION,
                                    "gitmatica.error.lvc_project.delete_version_failed",
                                    e,
                                    true
                            ),
                            () -> LvcGuiMessages.show(
                                    MessageType.INFO,
                                    "gitmatica.message.lvc_project.task_aborted",
                                    OPERATION_NAME
                            )
                    )
            );
            prepared = null;
            LvcOperationCoordinator.scheduleStarted(restoreWorld, task, OPERATION_NAME);
        }
        catch (Exception e)
        {
            if (prepared != null)
            {
                prepared.close();
            }

            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.DELETE_VERSION,
                    "gitmatica.error.lvc_project.delete_version_failed", e);
        }
    }

    private static void scheduleRemoteDelete(GuiLvcProjectController controller, LvcOperationHandle handle,
                                             Level restoreWorld, LvcCommitInfo commit) throws Exception
    {
        LvcLatestCommitUndoTarget target =
                LvcGitUndoOps.latestCommitUndoTarget(controller.gui.repositoryDirectory);
        String targetBranch = target.branchName();

        if (targetBranch == null || targetBranch.isBlank())
        {
            throw new IllegalStateException("LVC repository is not on a local branch");
        }

        controller.removeTrackingOverlay();
        LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.deleteVersion(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                target.parentCommitId(),
                targetBranch,
                LvcTaskCallbacks.of(
                        result ->
                        {
                            refreshAfterDelete(controller);
                            LvcGuiMessages.show(
                                    MessageType.SUCCESS,
                                    "gitmatica.message.lvc_project.deleted_version_delete_changes",
                                    commit.shortId(),
                                    LvcOperationCoordinator.regionCountText(result.regionCount())
                            );
                            showLossyRemoteApplyWarning(result);
                        },
                        e -> LvcGuiMessages.showTaskError(
                                Operation.DELETE_VERSION,
                                "gitmatica.error.lvc_project.delete_version_failed",
                                e,
                                true
                        ),
                        () -> LvcGuiMessages.show(
                                MessageType.INFO,
                                "gitmatica.message.lvc_project.task_aborted",
                                OPERATION_NAME
                        )
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, OPERATION_NAME);
        LvcDiagnostics.debug(
                "GuiLvcProjectManager: remote delete latest version scheduled repo='{}' branch='{}' commit='{}' parent='{}'",
                controller.gui.repositoryDirectory,
                targetBranch,
                target.commitId(),
                target.parentCommitId()
        );
    }

    private static boolean isLatestDeletableCommit(GuiLvcProjectController controller, LvcCommitInfo commit)
    {
        return commit != null &&
                !controller.gui.detachedHead &&
                controller.gui.history.size() > 1 &&
                !controller.gui.history.isEmpty() &&
                controller.gui.history.get(0).id().equals(commit.id());
    }

    private static void refreshAfterDelete(GuiLvcProjectController controller)
    {
        controller.refreshRepositoryState();
        controller.gui.refreshHistory();
        controller.gui.syncBranchDropdownSelection();
        controller.gui.updateTitle();

        if (Minecraft.getInstance().level != null)
        {
            controller.loadTrackingOverlay();
        }
        else
        {
            controller.focusTrackingOverlay();
        }
    }

    private static void showLossyRemoteApplyWarning(LvcRemoteServerApplyTask.Result result)
    {
        if (result.lossy())
        {
            LvcGuiMessages.show(MessageType.WARNING, "gitmatica.message.lvc_project.lossy_command_apply");
        }
    }
}
