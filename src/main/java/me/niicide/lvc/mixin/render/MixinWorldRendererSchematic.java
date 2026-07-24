package me.niicide.lvc.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifiers;

/**
 * Keeps filtered-out changes at normal depth while the render-through hotkey is
 * held. Included changes are rendered through blocks by the verifier marker
 * pass, using the filtered positions supplied by the verifier mixin.
 */
@Mixin(WorldRendererSchematic.class)
abstract class MixinWorldRendererSchematic
{
    @Redirect(
            method = "renderBlockOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;getBooleanValue()Z"))
    private boolean gitmatica$normalDepthForFilteredBase(
            ConfigBooleanHotkeyed option)
    {
        return this.gitmatica$hasActiveFilter()
                ? false
                : option.getBooleanValue();
    }

    @Redirect(
            method = "renderBlockOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/hotkeys/IKeybind;isKeybindHeld()Z"))
    private boolean gitmatica$normalDepthForFilteredBase(IKeybind keybind)
    {
        return this.gitmatica$hasActiveFilter()
                ? false
                : keybind.isKeybindHeld();
    }

    private boolean gitmatica$hasActiveFilter()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                .getSelectedSchematicPlacement();

        return placement != null &&
               placement.hasVerifier() &&
               GitmaticaVerifiers.extension(placement.getSchematicVerifier())
                       .gitmatica$getRenderThroughMismatchFilter()
                       .active();
    }
}
