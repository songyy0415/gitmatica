package me.niicide.lvc.gui;

import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.config.LvcHotkeys;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.storage.LvcSemanticRepository;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.hotkeys.IKeybind;

public final class LvcHotkeyActions
{
    private LvcHotkeyActions()
    {
    }

    public static boolean handle(IKeybind keybind)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null)
        {
            return false;
        }

        if (keybind == LvcHotkeys.OPEN_PROJECT_BROWSER.getKeybind())
        {
            GuiBase.openGui(new GuiLvcProjectBrowser());
            return true;
        }

        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        Path repositoryDirectory = selectedRepositoryDirectory(placement);

        if (placement == null || repositoryDirectory == null)
        {
            return false;
        }

        if (keybind == LvcHotkeys.OPEN_PROJECT_MANAGER.getKeybind())
        {
            LvcSchematicPlacementRowActions.openProjectManager(placement);
            return true;
        }

        if (keybind == LvcHotkeys.OPEN_PROJECT_EDITOR.getKeybind())
        {
            LvcSchematicPlacementRowActions.openProjectEditor(placement);
            return true;
        }

        if (keybind == LvcHotkeys.CONFIGURE_SUB_REGION.getKeybind())
        {
            try
            {
                String selectedSubRegionName =
                        LvcTrackingOverlayService.getSelectedTrackingSubRegion(repositoryDirectory);
                LvcDiagnostics.debug(
                        "Gitmatica configure sub-region hotkey invoked repo='{}' selectedRegion='{}'",
                        repositoryDirectory,
                        selectedSubRegionName
                );
                boolean opened = GuiLvcProjectEditor.openSelectedSubRegionDialog(
                        repositoryDirectory,
                        selectedSubRegionName
                );

                if (!opened)
                {
                    LvcGuiMessages.show(
                            MessageType.ERROR,
                            "litematica.error.lvc_project_editor.select_sub_region"
                    );
                }

                return true;
            }
            catch (Exception e)
            {
                LvcDiagnostics.warn("Gitmatica configure sub-region hotkey failed repo='{}' region='{}' error='{}'",
                        repositoryDirectory,
                        LvcTrackingOverlayService.getSelectedTrackingSubRegion(repositoryDirectory),
                        e.getMessage());
                LvcGuiMessages.show(
                        MessageType.ERROR,
                        "litematica.error.lvc_project.open_from_placement_failed",
                        e.getMessage()
                );
                return true;
            }
        }

        boolean undoLastSave = keybind == LvcHotkeys.UNDO_LAST_SAVE.getKeybind();
        GuiLvcProjectButtonType action = projectAction(keybind);

        if (!undoLastSave && action == null)
        {
            return false;
        }

        try
        {
            LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
            GuiLvcProjectManager gui = new GuiLvcProjectManager(repositoryDirectory, manifest.name());

            if (undoLastSave)
            {
                gui.handleHotkeyUndoLastSave();
            }
            else
            {
                gui.handleHotkeyAction(action);
            }

            return true;
        }
        catch (Exception e)
        {
            LvcDiagnostics.warn("Gitmatica hotkey action failed repo='{}' action={} error='{}'",
                    repositoryDirectory, action, e.getMessage());
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.lvc_project.open_from_placement_failed", e.getMessage());
            return true;
        }
    }

    @Nullable
    private static Path selectedRepositoryDirectory(@Nullable SchematicPlacement placement)
    {
        return placement != null ?
                LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile()) : null;
    }

    @Nullable
    private static GuiLvcProjectButtonType projectAction(IKeybind keybind)
    {
        if (keybind == LvcHotkeys.DISCARD_CHANGES.getKeybind())
        {
            return GuiLvcProjectButtonType.DISCARD_CHANGES;
        }
        else if (keybind == LvcHotkeys.CLEAR_AREA.getKeybind())
        {
            return GuiLvcProjectButtonType.CLEAR_AREA;
        }
        else if (keybind == LvcHotkeys.SAVE_VERSION.getKeybind())
        {
            return GuiLvcProjectButtonType.SAVE_VERSION;
        }

        return null;
    }
}
