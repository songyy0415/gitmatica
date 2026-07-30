package me.niicide.lvc.mixin.gui.widget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult.BlockMismatchInfo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;

@Mixin(BlockMismatchInfo.class)
abstract class MixinBlockMismatchInfo
{
    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/StringUtils;translate(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                    ordinal = 0),
            index = 0)
    private String gitmatica$beforeHeader(String original)
    {
        return this.gitmatica$useChangeHeaders()
                ? "gitmatica.gui.label.lvc_change_viewer.before"
                : original;
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/StringUtils;translate(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                    ordinal = 1),
            index = 0)
    private String gitmatica$afterHeader(String original)
    {
        return this.gitmatica$useChangeHeaders()
                ? "gitmatica.gui.label.lvc_change_viewer.after"
                : original;
    }

    private boolean gitmatica$useChangeHeaders()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                .getSelectedSchematicPlacement();

        return LvcTrackingOverlayService.isSemanticTrackingPlacement(placement);
    }
}
