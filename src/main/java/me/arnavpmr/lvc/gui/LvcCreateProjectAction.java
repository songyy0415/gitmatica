package me.arnavpmr.lvc.gui;

import me.arnavpmr.lvc.project.LvcProjectSelectionStorage;
import me.arnavpmr.lvc.project.LvcProjectPaths;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.LvcPlayerIdentity;
import me.arnavpmr.lvc.task.LvcOperationHandle;
import me.arnavpmr.lvc.task.LvcSemanticInitProjectTask;
import me.arnavpmr.lvc.task.LvcTaskCallbacks;
import me.arnavpmr.lvc.task.LvcTaskRegistry;
import me.arnavpmr.lvc.task.LvcTaskScheduling;
import me.arnavpmr.lvc.world.LvcWorldAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiSchematicSaveBase;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcCreateProjectAction
{
    private LvcCreateProjectAction()
    {
    }

    public static void promptFromSaveGui(GuiSchematicSaveBase gui)
    {
        CreateContext context = createContext(gui);

        if (context == null)
        {
            return;
        }

        if (LvcTaskRegistry.hasActiveOperation())
        {
            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return;
        }

        Path gameRunDirectory = context.minecraft().gameDirectory.toPath();
        GuiBase.openGui(new GuiLvcTextInputDialog(
                256,
                "gitmatica.gui.title.lvc_project_manager.create_project",
                "",
                gui,
                projectName -> validateProjectName(gameRunDirectory, projectName),
                (java.util.function.Consumer<String>) projectName -> createFromSaveGui(gui, projectName)
        ));
    }

    public static void createFromSaveGui(GuiSchematicSaveBase gui, @Nullable String repositoryName)
    {
        CreateContext context = createContext(gui);

        if (context == null)
        {
            return;
        }

        Minecraft minecraft = context.minecraft();
        String projectName = repositoryName == null ? "" : repositoryName.trim();
        String validationError = validateProjectName(minecraft.gameDirectory.toPath(), projectName);

        if (validationError != null)
        {
            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.create_failed", validationError);
            return;
        }

        LvcOperationHandle handle = null;

        try
        {
            LvcPlayerIdentity identity = new LvcPlayerIdentity(
                    context.player().getName().getString(), context.player().getUUID());
            Optional<LvcOperationHandle> acquiredHandle = LvcTaskRegistry.tryAcquire("LVC Create Project",
                    minecraft.gameDirectory.toPath());

            if (acquiredHandle.isEmpty())
            {
                gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                        LvcTaskRegistry.activeOperationName());
                return;
            }

            handle = acquiredHandle.get();
            LvcDiagnostics.debug("LvcCreateProjectAction: scheduling create project name='{}' regions={} player='{}'",
                    projectName, context.validRegionCount(), identity.name());
            var captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(minecraft.level);
            LvcSemanticInitProjectTask task = new LvcSemanticInitProjectTask(
                    handle,
                    minecraft.gameDirectory.toPath(),
                    projectName,
                    identity,
                    captureWorld,
                    context.selection(),
                    LvcTaskCallbacks.of(
                            result ->
                            {
                                String createdProjectName = result.repositoryDirectory().getFileName().toString();
                                Path displayPath = LvcProjectPaths.minecraftDisplayPath(
                                        minecraft.gameDirectory.toPath(), result.repositoryDirectory());
                                GuiLvcProjectManager projectGui = new GuiLvcProjectManager(
                                        result.repositoryDirectory(), createdProjectName);
                                GuiBase.openGui(projectGui);
                                projectGui.addMessage(MessageType.SUCCESS, "gitmatica.message.lvc_project.created",
                                        displayPath, result.commitId());
                            },
                            e ->
                            {
                                if (e instanceof FileAlreadyExistsException)
                                {
                                    LvcGuiMessages.show(MessageType.ERROR,
                                            "gitmatica.error.lvc_project_manager.project_name_used");
                                }
                                else
                                {
                                    LvcGuiMessages.show(MessageType.ERROR,
                                            "gitmatica.error.lvc_project.create_failed", e.getMessage());
                                }
                            },
                            () -> LvcGuiMessages.show(MessageType.INFO,
                                    "gitmatica.message.lvc_project.task_aborted", "LVC Create Project")
                    )
            );
            LvcTaskScheduling.scheduleForWorld(captureWorld, task);
            LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_started", "LVC Create Project");
        }
        catch (Exception e)
        {
            if (handle != null)
            {
                LvcTaskRegistry.release(handle);
            }

            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.create_failed", e.getMessage());
        }
    }

    @Nullable
    private static CreateContext createContext(GuiSchematicSaveBase gui)
    {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null)
        {
            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.no_player");
            return null;
        }

        if (minecraft.level == null)
        {
            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return null;
        }

        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (selection == null || selection.getAllSubRegionBoxes().isEmpty())
        {
            gui.addMessage(MessageType.ERROR, "litematica.message.error.schematic_save_no_area_selected");
            return null;
        }

        int validRegionCount = LvcProjectSelectionStorage.countValidSelectionRegions(selection);

        if (validRegionCount <= 0)
        {
            gui.addMessage(MessageType.ERROR, "gitmatica.error.lvc_project.simple_selection_required");
            return null;
        }

        return new CreateContext(minecraft, player, selection, validRegionCount);
    }

    @Nullable
    private static String validateProjectName(Path gameRunDirectory, @Nullable String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            return StringUtils.translate("gitmatica.error.lvc_project_editor.project_name_required");
        }

        try
        {
            String normalizedProjectName = projectName.trim();
            LvcProjectSelectionStorage.validateProjectName(normalizedProjectName);
            Path repositoryDirectory = LvcProjectPaths.repositoryDirectory(gameRunDirectory, normalizedProjectName);

            if (Files.exists(repositoryDirectory))
            {
                return StringUtils.translate("gitmatica.error.lvc_project_manager.project_name_used");
            }

            return null;
        }
        catch (IllegalArgumentException e)
        {
            LvcDiagnostics.debug("LvcCreateProjectAction: rejected project name input='{}' error='{}'",
                    projectName, e.getMessage());
            return StringUtils.translate("gitmatica.error.lvc_project_manager.project_name_invalid");
        }
    }

    private record CreateContext(Minecraft minecraft, Player player, AreaSelection selection, int validRegionCount)
    {
    }
}
