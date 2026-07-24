package me.niicide.lvc.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.OverlayRenderer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.data.Color4f;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;

@Mixin(OverlayRenderer.class)
abstract class MixinOverlayRenderer
{
    @Redirect(
            method = "renderSchematicMismatches",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;getColor()Lfi/dy/masa/malilib/util/data/Color4f;"))
    private Color4f gitmatica$semanticMismatchColor(MismatchType type)
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                .getSelectedSchematicPlacement();

        return LvcTrackingOverlayService.isSemanticTrackingPlacement(placement)
                ? LvcTrackingOverlayService.semanticTrackingMismatchColor(type)
                : type.getColor();
    }
}
