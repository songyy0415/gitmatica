package me.arnavpmr.lvc.mixin.network;

import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.CompoundTag;

import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import me.arnavpmr.lvc.capture.LvcServuxBulkEntityCache;

@Mixin(ServuxLitematicaHandler.class)
abstract class MixinServuxLitematicaHandler
{
    @Inject(method = "handleBulkData", at = @At("HEAD"))
    private void gitmatica$captureBulkReply(
            int transactionId,
            @Nullable CompoundTag data,
            CallbackInfo callbackInfo)
    {
        if (data != null && !data.isEmpty())
        {
            LvcServuxBulkEntityCache.recordBulkReply(data);
        }
    }
}
