package me.niicide.lvc.gui;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcRemoteServerApplyTask;
import me.niicide.lvc.task.LvcSemanticCheckoutTask;
import me.niicide.lvc.task.LvcSemanticScanTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;

final class LvcCheckoutWorkflow
{
    private LvcCheckoutWorkflow()
    {
    }

    static void promptCheckoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Checkout");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            CheckoutTarget target = CheckoutTarget.commit(commit);

            if (rejectAlreadyLoaded(controller, handle.get(), target))
            {
                return;
            }

            LvcWorldBackend backend = LvcWorldBackend.resolve(minecraft.level);

            if (backend != LvcWorldBackend.DIRECT)
            {
                scheduleRemoteCheckoutPreflight(controller, handle.get(), minecraft.level, target);
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            scheduleCheckoutPreflight(controller, handle.get(), restoreWorld, target);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.CHECKOUT, "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    static void checkoutCommit(GuiLvcProjectController controller, LvcProjectService.CommitInfo commit)
    {
        promptCheckoutCommit(controller, commit);
    }

    static void promptCheckoutBranch(GuiLvcProjectController controller, String branchName)
    {
        if (LvcTaskRegistry.hasActiveOperation())
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        if (branchName == null || branchName.isBlank())
        {
            controller.gui.syncBranchDropdownSelection();
            return;
        }

        String trimmedBranchName = branchName.trim();

        try
        {
            String targetCommitId = LvcProjectService.localBranchTipCommitId(controller.gui.repositoryDirectory, trimmedBranchName);
            CheckoutTarget target = CheckoutTarget.branch(trimmedBranchName, targetCommitId);

            if (target.isAlreadyLoaded(controller))
            {
                controller.gui.syncBranchDropdownSelection();
                controller.showAlreadyAtVersion(targetCommitId);
                return;
            }

            if (LvcProjectService.headMatchesCommit(controller.gui.repositoryDirectory, targetCommitId))
            {
                checkoutBranchWithoutRestore(controller, trimmedBranchName);
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.level == null)
            {
                controller.gui.syncBranchDropdownSelection();
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.no_world");
                return;
            }

            Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Checkout Branch");

            if (handle.isEmpty())
            {
                controller.gui.syncBranchDropdownSelection();
                return;
            }

            LvcWorldBackend backend = LvcWorldBackend.resolve(minecraft.level);

            if (backend != LvcWorldBackend.DIRECT)
            {
                scheduleRemoteCheckoutPreflight(controller, handle.get(), minecraft.level, target);
                return;
            }

            Level restoreWorld = LvcWorldAccess.resolveSemanticRestoreWorld(minecraft.level);
            scheduleCheckoutPreflight(controller, handle.get(), restoreWorld, target);
        }
        catch (Exception e)
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.showTaskError(Operation.CHECKOUT_BRANCH, "litematica.error.lvc_project.checkout_failed", e);
            LvcDiagnostics.debug("LVC branch checkout failed before preflight branch='{}' error='{}'",
                    trimmedBranchName, e.getMessage());
        }
    }

    private static void checkoutBranchWithoutRestore(GuiLvcProjectController controller, String branchName)
    {
        try
        {
            LvcProjectService.checkoutBranchToWorkingTree(controller.gui.repositoryDirectory, branchName);

            if (LvcProjectService.isCurrentTrackingOverlayLoaded(controller.gui.repositoryDirectory))
            {
                controller.focusTrackingOverlay();
            }
            else
            {
                controller.loadTrackingOverlay();
            }

            controller.refreshRepositoryState();
            controller.gui.refreshHistory();
            controller.gui.initGui();
            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.switched_branch", branchName);
            LvcDiagnostics.debug("LVC branch checkout completed without restore repo='{}' branch='{}'",
                    controller.gui.repositoryDirectory, branchName);
        }
        catch (Exception e)
        {
            controller.gui.syncBranchDropdownSelection();
            LvcGuiMessages.showTaskError(Operation.CHECKOUT_BRANCH, "litematica.error.lvc_project.checkout_failed", e);
            LvcDiagnostics.debug("LVC branch checkout without restore failed repo='{}' branch='{}' error='{}'",
                    controller.gui.repositoryDirectory, branchName, e.getMessage());
        }
    }

    private static void scheduleCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                  Level restoreWorld, CheckoutTarget target)
    {
        LvcSemanticCheckoutTask.Preflight task = new LvcSemanticCheckoutTask.Preflight(
                handle,
                controller.gui.repositoryDirectory,
                restoreWorld,
                target.commitId(),
                LvcTaskCallbacks.of(
                        result -> handleCheckoutPreflight(controller, handle, target, result),
                        e ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
                        },
                        () ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                        }
                )
        );
        LvcOperationCoordinator.scheduleStarted(restoreWorld, task, target.taskName());
    }

    private static void scheduleRemoteCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                        Level world, CheckoutTarget target)
    {
        LvcSemanticScanTask[] taskRef = new LvcSemanticScanTask[1];
        LvcSemanticScanTask task = new LvcSemanticScanTask(
                handle,
                controller.gui.repositoryDirectory,
                world,
                LvcTaskCallbacks.of(
                        result -> handleRemoteCheckoutPreflight(controller, handle, world, target, result,
                                taskRef[0].furnaceXpCleanupCandidates()),
                        e ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
                        },
                        () ->
                        {
                            target.syncBranchDropdownIfNeeded(controller);
                            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                        }
                ),
                false,
                true
        );
        taskRef[0] = task;
        LvcTaskScheduling.scheduleClient(task);
    }

    private static void handleRemoteCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                      Level world, CheckoutTarget target,
                                                      LvcProjectService.SemanticScanResult scan,
                                                      List<BlockPos> furnaceXpCleanupCandidates)
    {
        try
        {
            if (scan.unknownChunks() > 0)
            {
                LvcTaskRegistry.release(handle);
                target.syncBranchDropdownIfNeeded(controller);
                LvcGuiMessages.showUnloadedTrackedChunks(target.operation(),
                        "litematica.error.lvc_project.checkout_failed", scan.unknownChunks());
                return;
            }

            boolean gitDirty = LvcProjectService.hasUncommittedChanges(controller.gui.repositoryDirectory);

            if (!scan.clean() || gitDirty)
            {
                LvcTaskRegistry.release(handle);
                target.syncBranchDropdownIfNeeded(controller);
                LvcDiagnostics.debug("LVC remote checkout preflight dirty repo='{}' changedChunks={} addedChunks={} removedChunks={} gitDirty={}",
                        controller.gui.repositoryDirectory, scan.changedChunks(), scan.addedChunks(),
                        scan.removedChunks(), gitDirty);
                LvcOperationCoordinator.showUnsavedChangesNotice(controller, target.taskName());
                return;
            }

            openRemoteCheckoutConfirm(controller, handle, world, target, furnaceXpCleanupCandidates);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            target.syncBranchDropdownIfNeeded(controller);
            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    private static void handleCheckoutPreflight(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                CheckoutTarget target,
                                                LvcSemanticCheckoutTask.PreflightResult result)
    {
        LvcDiagnostics.debug("LVC Checkout preflight result commit={} branch='{}' gitDirty={} currentChangedBlocks={} currentChangedChunks={} targetChangedBlocks={} targetChangedChunks={}",
                target.commitId(), target.branchName() == null ? "<none>" : target.branchName(),
                result.gitChanges(), result.currentDiff().changedBlocks(), result.currentDiff().changedChunks(),
                result.targetDiff().changedBlocks(), result.targetDiff().changedChunks());

        if (result.hasUncommittedChanges())
        {
            result.prepared().close();
            LvcTaskRegistry.release(handle);
            target.syncBranchDropdownIfNeeded(controller);
            LvcOperationCoordinator.showUnsavedChangesNotice(controller, target.taskName());
            return;
        }

        openCheckoutConfirm(controller, handle, result.prepared(), target);
    }

    private static boolean rejectAlreadyLoaded(GuiLvcProjectController controller, LvcOperationHandle handle,
                                               CheckoutTarget target) throws IOException
    {
        if (!target.isAlreadyLoaded(controller))
        {
            return false;
        }

        LvcTaskRegistry.release(handle);
        target.syncBranchDropdownIfNeeded(controller);
        controller.showAlreadyAtVersion(target.commitId());
        return true;
    }

    private static void openCheckoutConfirm(GuiLvcProjectController controller, LvcOperationHandle handle,
                                            LvcSemanticCheckoutTask.PreparedCheckout prepared, CheckoutTarget target)
    {
        if (!Configs.Generic.LVC_SHOW_CHECKOUT_WARNING.getBooleanValue())
        {
            LvcDiagnostics.debug("LVC Checkout confirmation skipped by global config repo='{}' target='{}'",
                    controller.gui.repositoryDirectory, target.displayName());
            schedulePreparedCheckout(controller, handle, prepared, target);
            return;
        }

        GuiBase.openGui(new GuiLvcWarningConfirmDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.confirm_checkout",
                "litematica.gui.message.lvc_project.confirm_checkout",
                Configs.Generic.LVC_SHOW_CHECKOUT_WARNING,
                target.taskName(),
                () -> schedulePreparedCheckout(controller, handle, prepared, target),
                () ->
                {
                    prepared.close();
                    LvcTaskRegistry.release(handle);
                    target.syncBranchDropdownIfNeeded(controller);
                }
        ));
    }

    private static void openRemoteCheckoutConfirm(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                  Level world, CheckoutTarget target,
                                                  List<BlockPos> furnaceXpCleanupCandidates)
    {
        if (!Configs.Generic.LVC_SHOW_CHECKOUT_WARNING.getBooleanValue())
        {
            LvcDiagnostics.debug("LVC remote Checkout confirmation skipped by global config repo='{}' target='{}'",
                    controller.gui.repositoryDirectory, target.displayName());
            scheduleRemoteCheckout(controller, handle, world, target, furnaceXpCleanupCandidates);
            return;
        }

        GuiBase.openGui(new GuiLvcWarningConfirmDialog(
                LvcOperationCoordinator.confirmParent(controller),
                "litematica.gui.title.lvc_project.confirm_checkout",
                "litematica.gui.message.lvc_project.confirm_checkout",
                Configs.Generic.LVC_SHOW_CHECKOUT_WARNING,
                target.taskName(),
                () -> scheduleRemoteCheckout(controller, handle, world, target, furnaceXpCleanupCandidates),
                () ->
                {
                    LvcTaskRegistry.release(handle);
                    target.syncBranchDropdownIfNeeded(controller);
                }
        ));
    }

    private static void schedulePreparedCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                                 LvcSemanticCheckoutTask.PreparedCheckout prepared,
                                                 CheckoutTarget target)
    {
        try
        {
            controller.removeTrackingOverlay();
            LvcSemanticCheckoutTask.Apply task = new LvcSemanticCheckoutTask.Apply(
                    handle,
                    prepared,
                    target.branchName(),
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                controller.loadTrackingOverlay();
                                if (!target.reattachIfNeeded(controller))
                                {
                                    return;
                                }

                                controller.gui.initGui();
                                target.showSuccess(result.regionCount());
                            },
                            e ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e, true);
                            },
                            () ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                            }
                    )
            );
            LvcTaskScheduling.scheduleForWorld(prepared.world(), task);
        }
        catch (Exception e)
        {
            prepared.close();
            LvcTaskRegistry.release(handle);
            target.syncBranchDropdownIfNeeded(controller);
            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    private static void scheduleRemoteCheckout(GuiLvcProjectController controller, LvcOperationHandle handle,
                                               Level world, CheckoutTarget target,
                                               List<BlockPos> furnaceXpCleanupCandidates)
    {
        try
        {
            controller.removeTrackingOverlay();
            LvcRemoteServerApplyTask task = LvcRemoteServerApplyTask.checkout(
                    handle,
                    controller.gui.repositoryDirectory,
                    world,
                    target.commitId(),
                    target.branchName(),
                    furnaceXpCleanupCandidates,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                controller.loadTrackingOverlay();
                                if (!target.reattachIfNeeded(controller))
                                {
                                    return;
                                }

                                controller.gui.initGui();
                                target.showSuccess(result.regionCount());
                                showLossyRemoteWarning(result);
                            },
                            e ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e, true);
                            },
                            () ->
                            {
                                target.syncBranchDropdownIfNeeded(controller);
                                LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", target.taskName());
                            }
                    )
            );
            LvcTaskScheduling.scheduleClient(task);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle);
            target.syncBranchDropdownIfNeeded(controller);
            LvcGuiMessages.showTaskError(target.operation(), "litematica.error.lvc_project.checkout_failed", e);
        }
    }

    private static void showLossyRemoteWarning(LvcRemoteServerApplyTask.Result result)
    {
        if (result.lossy())
        {
            LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_apply");
        }
    }

    private record CheckoutTarget(String commitId, String displayName, @Nullable String branchName, boolean branchSwitch)
    {
        private static CheckoutTarget commit(LvcProjectService.CommitInfo commit)
        {
            return new CheckoutTarget(commit.id(), commit.shortId(), null, false);
        }

        private static CheckoutTarget branch(String branchName, String commitId)
        {
            return new CheckoutTarget(commitId, branchName, branchName, true);
        }

        private String taskName()
        {
            return this.branchSwitch ? "LVC Checkout Branch" : "LVC Checkout";
        }

        private Operation operation()
        {
            return this.branchSwitch ? Operation.CHECKOUT_BRANCH : Operation.CHECKOUT;
        }

        private boolean isAlreadyLoaded(GuiLvcProjectController controller) throws IOException
        {
            return this.branchSwitch ?
                    controller.isBranchAlreadyLoaded(this.branchName, this.commitId) :
                    controller.isVersionAlreadyLoaded(this.commitId);
        }

        private boolean reattachIfNeeded(GuiLvcProjectController controller)
        {
            if (this.branchSwitch)
            {
                return true;
            }

            try
            {
                LvcProjectService.reattachHeadToBranchIfAtTip(controller.gui.repositoryDirectory,
                        controller.gui.checkoutBranchName);
                return true;
            }
            catch (Exception e)
            {
                LvcGuiMessages.showTaskError(this.operation(), "litematica.error.lvc_project.checkout_failed", e);
                return false;
            }
        }

        private void showSuccess(int regionCount)
        {
            if (this.branchSwitch)
            {
                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.checked_out_branch",
                        this.displayName, LvcOperationCoordinator.regionCountText(regionCount));
                return;
            }

            LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.checked_out",
                    this.displayName, LvcOperationCoordinator.regionCountText(regionCount));
        }

        private void syncBranchDropdownIfNeeded(GuiLvcProjectController controller)
        {
            if (this.branchSwitch)
            {
                controller.gui.syncBranchDropdownSelection();
            }
        }
    }
}
