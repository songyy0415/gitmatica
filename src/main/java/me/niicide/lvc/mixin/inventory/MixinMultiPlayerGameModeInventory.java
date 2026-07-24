package me.niicide.lvc.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

@Mixin(value = MultiPlayerGameMode.class, priority = 970)
abstract class MixinMultiPlayerGameModeInventory
{
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void gitmatica$rememberOpenedContainer(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> callbackInfo)
    {
        if (callbackInfo.getReturnValue().consumesAction())
        {
            VerifierInventoryOverlay.onContainerClick(hitResult);
        }
    }
}
