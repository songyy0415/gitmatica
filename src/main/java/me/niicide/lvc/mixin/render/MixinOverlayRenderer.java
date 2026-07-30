package me.niicide.lvc.mixin.render;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.OverlayRenderer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.data.Color4f;
import me.niicide.lvc.overlay.LvcBlockInspectionPolicy;
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
        return this.gitmatica$isSemanticTrackingPlacement()
                ? LvcTrackingOverlayService.semanticTrackingMismatchColor(type)
                : type.getColor();
    }

    @ModifyVariable(
            method = "renderBlockInfoOverlay",
            at = @At("STORE"),
            ordinal = 0)
    private BlockState gitmatica$allowAirInSemanticBlockComparison(BlockState original)
    {
        return LvcBlockInspectionPolicy.comparisonAirSentinel(
                this.gitmatica$isSemanticTrackingPlacement());
    }

    @Redirect(
            method = "updateBlockInfoLines",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean gitmatica$allowAirInSemanticCompactComparison(BlockState state)
    {
        return LvcBlockInspectionPolicy.isAirForCompactComparison(
                this.gitmatica$isSemanticTrackingPlacement(), state);
    }

    @Redirect(
            method = "updateBlockInfoLines",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
                    ordinal = 0))
    private boolean gitmatica$beforeCompactHeading(
            List<String> lines,
            Object original)
    {
        return lines.add(LvcBlockInspectionPolicy.comparisonHeading(
                this.gitmatica$isSemanticTrackingPlacement(),
                true,
                (String) original));
    }

    @Redirect(
            method = "updateBlockInfoLines",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
                    ordinal = 2))
    private boolean gitmatica$afterCompactHeading(
            List<String> lines,
            Object original)
    {
        return lines.add(LvcBlockInspectionPolicy.comparisonHeading(
                this.gitmatica$isSemanticTrackingPlacement(),
                false,
                (String) original));
    }

    private boolean gitmatica$isSemanticTrackingPlacement()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                .getSelectedSchematicPlacement();
        return LvcTrackingOverlayService.isSemanticTrackingPlacement(placement);
    }
}
