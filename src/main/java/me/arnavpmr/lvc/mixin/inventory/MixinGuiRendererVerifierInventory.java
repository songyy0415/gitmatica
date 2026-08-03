package me.arnavpmr.lvc.mixin.inventory;

import javax.annotation.Nullable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

@Mixin(GuiRenderer.class)
public class MixinGuiRendererVerifierInventory
{
    @Inject(method = "render()V", at = @At("RETURN"))
    private void gitmatica_clearTransparentVerifierItemStates(CallbackInfo ci)
    {
        VerifierInventoryOverlay.transparentItemStates.clear();
    }

    @WrapOperation(method = "submitBlitFromItemAtlas(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;Lnet/minecraft/client/gui/render/GuiItemAtlas$SlotView;)V",
                   at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/state/gui/BlitRenderState;"))
    private BlitRenderState gitmatica_makeVerifierItemAtlasTransparent(RenderPipeline pipeline,
                                                                        TextureSetup textureSetup,
                                                                        Matrix3x2fc pose,
                                                                        int x1,
                                                                        int y1,
                                                                        int x2,
                                                                        int y2,
                                                                        float u1,
                                                                        float u2,
                                                                        float v1,
                                                                        float v2,
                                                                        int color,
                                                                        @Nullable ScreenRectangle scissorArea,
                                                                        @Nullable ScreenRectangle bounds,
                                                                        Operation<BlitRenderState> original,
                                                                        @Local(argsOnly = true) GuiItemRenderState state)
    {
        if (VerifierInventoryOverlay.transparentItemStates.contains(state))
        {
            color = ARGB.color(Math.round(ARGB.alpha(color) * VerifierInventoryOverlay.MISSING_ITEM_ALPHA), color);
            pipeline = RenderPipelines.GUI_TEXTURED;
        }

        return original.call(pipeline, textureSetup, pose, x1, y1, x2, y2, u1, u2, v1, v2, color, scissorArea, bounds);
    }
}
