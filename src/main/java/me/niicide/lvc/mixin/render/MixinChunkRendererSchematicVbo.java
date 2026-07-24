package me.niicide.lvc.mixin.render;

import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.malilib.util.data.Color4f;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifiers;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;

/**
 * Adds Gitmatica semantics to Litematica's existing overlay mesh build without
 * duplicating or replacing its renderer.
 */
@Mixin(ChunkRendererSchematicVbo.class)
abstract class MixinChunkRendererSchematicVbo
{
    @Unique
    private static final ThreadLocal<RenderContext> GITMATICA$RENDER_CONTEXT =
            new ThreadLocal<>();

    @Shadow
    protected abstract OverlayType getOverlayType(
            BlockState schematicState, BlockState clientState);

    @Shadow
    @Nullable
    protected static Color4f getOverlayColor(OverlayType overlayType)
    {
        throw new AssertionError();
    }

    @Inject(method = "renderBlocksAndOverlay", at = @At("HEAD"))
    private void gitmatica$beginSemanticOverlay(
            BlockModelRendererSchematic blockRenderer,
            FluidModelRendererSchematic fluidRenderer,
            BlockPos position,
            ChunkRenderDataSchematic data,
            ChunkMeshDataSchematic meshData,
            ChunkRenderDispatcherBuffers buffers,
            IBlockOutputSchematic output,
            Vec3 offset,
            VisGraph visibility,
            CallbackInfo callbackInfo)
    {
        GITMATICA$RENDER_CONTEXT.set(this.gitmatica$contextAt(position));
    }

    @Inject(method = "renderBlocksAndOverlay", at = @At("RETURN"))
    private void gitmatica$endSemanticOverlay(
            BlockModelRendererSchematic blockRenderer,
            FluidModelRendererSchematic fluidRenderer,
            BlockPos position,
            ChunkRenderDataSchematic data,
            ChunkMeshDataSchematic meshData,
            ChunkRenderDispatcherBuffers buffers,
            IBlockOutputSchematic output,
            Vec3 offset,
            VisGraph visibility,
            CallbackInfo callbackInfo)
    {
        GITMATICA$RENDER_CONTEXT.remove();
    }

    @Redirect(
            method = "renderBlocksAndOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/render/schematic/ChunkRendererSchematicVbo;getOverlayType(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Lfi/dy/masa/litematica/util/OverlayType;"))
    private OverlayType gitmatica$inventoryMismatchOverlay(
            ChunkRendererSchematicVbo renderer,
            BlockState schematicState,
            BlockState clientState)
    {
        RenderContext context = GITMATICA$RENDER_CONTEXT.get();
        return context != null && context.inventoryMismatch()
                ? OverlayType.WRONG_STATE
                : this.getOverlayType(schematicState, clientState);
    }

    @Redirect(
            method = "renderBlocksAndOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/render/schematic/ChunkRendererSchematicVbo;getOverlayColor(Lfi/dy/masa/litematica/util/OverlayType;)Lfi/dy/masa/malilib/util/data/Color4f;"))
    private Color4f gitmatica$semanticOverlayColor(OverlayType type)
    {
        Color4f configuredColor = getOverlayColor(type);
        RenderContext context = GITMATICA$RENDER_CONTEXT.get();

        if (configuredColor != null && context != null && context.trackingOverlay())
        {
            Color4f semanticColor =
                    LvcTrackingOverlayService.semanticTrackingOverlayColor(type);
            return semanticColor != null ? semanticColor : configuredColor;
        }

        return configuredColor;
    }

    @Unique
    private RenderContext gitmatica$contextAt(BlockPos position)
    {
        boolean tracking = false;
        boolean inventoryMismatch = false;

        for (PlacementPart part : DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(position))
        {
            if (!part.getBox().contains(position))
            {
                continue;
            }

            SchematicPlacement placement = part.getPlacement();
            tracking |= LvcTrackingOverlayService.isSemanticTrackingPlacement(placement);

            if (placement.hasVerifier())
            {
                SchematicVerifier verifier = placement.getSchematicVerifier();
                inventoryMismatch |= GitmaticaVerifiers.extension(verifier)
                        .gitmatica$hasInventoryMismatchForOverlay(position);
            }
        }

        return new RenderContext(tracking, inventoryMismatch);
    }

    @Unique
    private record RenderContext(boolean trackingOverlay, boolean inventoryMismatch)
    {
    }
}
