package me.niicide.lvc.mixin.lifecycle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.data.EntityDataManager;
import me.niicide.lvc.capture.LvcServuxBulkEntityCache;

@Mixin(EntityDataManager.class)
abstract class MixinEntityDataManager
{
    @Inject(method = "reset", at = @At("TAIL"))
    private void gitmatica$clearBulkCaptureCache(boolean logout, CallbackInfo callbackInfo)
    {
        LvcServuxBulkEntityCache.clear();
    }
}
