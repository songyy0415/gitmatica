package me.niicide.lvc.mixin.hotkey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.gui.GuiLvcChangeViewer;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;

@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys")
abstract class MixinKeyCallbackHotkeys
{
    @Inject(method = "onKeyAction", at = @At("HEAD"), cancellable = true)
    private void gitmatica$openChangeViewerForTrackingPlacement(
            KeyAction action,
            IKeybind key,
            CallbackInfoReturnable<Boolean> callbackInfo)
    {
        if (key != Hotkeys.OPEN_GUI_SCHEMATIC_VERIFIER.getKeybind())
        {
            return;
        }

        SchematicPlacement placement =
                DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null && LvcTrackingOverlayService.isSemanticTrackingPlacement(placement))
        {
            LvcDiagnostics.info(
                    "opening Gitmatica Change Viewer for placement='{}'",
                    placement.getName());
            GuiBase.openGui(new GuiLvcChangeViewer(placement));
            callbackInfo.setReturnValue(true);
        }
    }
}
