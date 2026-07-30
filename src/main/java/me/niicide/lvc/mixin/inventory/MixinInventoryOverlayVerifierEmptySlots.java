package me.niicide.lvc.mixin.inventory;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

/**
 * TechUtils owns the same call at the default mixin priority and always allows
 * empty slots. This optional lower-priority fallback is only needed when
 * TechUtils is absent.
 */
@Mixin(
        value = fi.dy.masa.malilib.render.InventoryOverlay.class,
        priority = 900)
abstract class MixinInventoryOverlayVerifierEmptySlots
{
    @Redirect(
            method = "renderInventoryStacks(Lfi/dy/masa/malilib/render/GuiContext;Lfi/dy/masa/malilib/render/InventoryOverlayType;Lnet/minecraft/world/Container;IIIIILjava/util/Set;DD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;isEmpty()Z"),
            require = 0)
    private static boolean gitmatica$allowVerifierGhostSlots(ItemStack stack)
    {
        return VerifierInventoryOverlay.infoOverlayInstance == null &&
               stack.isEmpty();
    }
}
