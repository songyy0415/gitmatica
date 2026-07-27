package me.niicide.lvc.gui;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.eclipse.jgit.revwalk.RevCommit;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.LvcPlayerIdentity;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcSemanticCommitTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.world.LvcWorldAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;

final class LvcVersionWorkflow
{
    private LvcVersionWorkflow()
    {
    }

    static void commitStoredSelectionWithCurrentSelectionFallback(GuiLvcProjectController controller, String message)
    {
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

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Save Version");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            if (LvcProjectService.isDetachedHead(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.detached_head_save_version");
                return;
            }

            Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            LvcSemanticCommitTask task = new LvcSemanticCommitTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    captureWorld,
                    identity,
                    message,
                    LvcSemanticCommitTask.Mode.SAVE_VERSION,
                    List.of(),
                    LvcTaskCallbacks.of(
                            result -> handleCommitResult(controller, result),
                            e -> LvcGuiMessages.showTaskError(Operation.SAVE_VERSION, "litematica.error.lvc_project.commit_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Save Version")
                    )
            );
            LvcOperationCoordinator.scheduleStarted(captureWorld, task, "LVC Save Version");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.SAVE_VERSION, "litematica.error.lvc_project.commit_failed", e);
        }
    }

    static void updateAreasFromCurrentSelection(GuiLvcProjectController controller)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level world = minecraft.level;
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

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

        if (selection == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed",
                    StringUtils.translate("litematica.error.lvc_project.no_selection"));
            return;
        }

        Optional<LvcOperationHandle> handle = LvcOperationCoordinator.acquire(controller, "LVC Update Areas");

        if (handle.isEmpty())
        {
            return;
        }

        try
        {
            if (LvcProjectService.isDetachedHead(controller.gui.repositoryDirectory))
            {
                LvcTaskRegistry.release(handle.get());
                LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.update_areas_failed",
                        StringUtils.translate("litematica.error.lvc_project.detached_head_commit"));
                return;
            }

            Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(world);
            LvcPlayerIdentity identity = new LvcPlayerIdentity(player.getName().getString(), player.getUUID());
            var state = LvcProjectService.readSemanticProjectEditorState(controller.gui.repositoryDirectory);
            List<LvcManifest.Region> updatedRegions =
                    LvcProjectService.createRegionsFromSelection(
                            selection, state.placementOrigin());
            LvcSemanticCommitTask task = new LvcSemanticCommitTask(
                    handle.get(),
                    controller.gui.repositoryDirectory,
                    captureWorld,
                    identity,
                    "update areas",
                    LvcSemanticCommitTask.Mode.UPDATE_AREAS,
                    updatedRegions,
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                if (result.commit() == null)
                                {
                                    controller.focusTrackingOverlay();
                                    controller.gui.initGui();
                                    LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.nothing_to_commit");
                                    return;
                                }

                                controller.loadTrackingOverlay();
                                controller.gui.initGui();
                                LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.update_areas_updated",
                                        LvcOperationCoordinator.regionCountText(result.regionCount()));

                                if (result.lossyCapture())
                                {
                                    LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_commit");
                                }
                            },
                            e -> LvcGuiMessages.showTaskError(Operation.UPDATE_AREAS, "litematica.error.lvc_project.update_areas_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.task_aborted", "LVC Update Areas")
                    )
            );
            LvcOperationCoordinator.scheduleStarted(captureWorld, task, "LVC Update Areas");
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.UPDATE_AREAS, "litematica.error.lvc_project.update_areas_failed", e);
        }
    }

    private static void handleCommitResult(GuiLvcProjectController controller, LvcSemanticCommitTask.Result result)
    {
        RevCommit commit = result.commit();

        if (commit == null)
        {
            controller.focusTrackingOverlay();
            controller.refreshRepositoryState();
            controller.gui.refreshHistory();
            controller.gui.syncBranchDropdownSelection();
            controller.gui.updateTitle();
            LvcDiagnostics.debug("LVC Save Version completed as no-op repo='{}'", controller.gui.repositoryDirectory);
            LvcGuiMessages.show(MessageType.INFO, "litematica.message.lvc_project.nothing_to_commit");
            return;
        }

        controller.loadTrackingOverlay();
        controller.gui.focusHistoryCommitAfterNextRefresh(commit.getName());
        controller.gui.initGui();
        String shortCommitId = shortCommitId(commit);
        LvcDiagnostics.debug("LVC Save Version completed commit={}", shortCommitId);
        LvcGuiMessages.show(MessageType.SUCCESS, "litematica.message.lvc_project.committed", shortCommitId);

        if (result.lossyCapture())
        {
            LvcGuiMessages.show(MessageType.WARNING, "litematica.message.lvc_project.lossy_command_commit");
        }
    }

    private static String shortCommitId(RevCommit commit)
    {
        String commitId = commit.getName();
        return commitId.substring(0, Math.min(8, commitId.length()));
    }

}
