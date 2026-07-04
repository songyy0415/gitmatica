package fi.dy.masa.litematica.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.schematic.verifier.inventory.VerifierInventoryOverlay;

@Mixin(Gui.class)
public class MixinGui
{
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void litematica_onSetScreen(Screen screen, CallbackInfo ci)
    {
        VerifierInventoryOverlay.onScreenChanged(screen);
    }
}
