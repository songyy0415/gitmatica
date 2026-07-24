package me.niicide.lvc.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

@Mixin(Gui.class)
abstract class MixinMinecraftGui
{
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void gitmatica$onScreenChanged(Screen screen, CallbackInfo callbackInfo)
    {
        VerifierInventoryOverlay.onScreenChanged(screen);
    }
}
