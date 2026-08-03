package me.arnavpmr.lvc.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

@Mixin(ClientPacketListener.class)
abstract class MixinClientPacketListenerInventory
{
    @Inject(method = "handleBlockEntityData", at = @At("RETURN"))
    private void gitmatica$markVerifierBlockEntityChanged(
            ClientboundBlockEntityDataPacket packet,
            CallbackInfo callbackInfo)
    {
        SchematicVerifier.markVerifierBlockChanges(packet.getPos());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void gitmatica$markOpenContainerSlotChanged(
            ClientboundContainerSetSlotPacket packet,
            CallbackInfo callbackInfo)
    {
        VerifierInventoryOverlay.markOpenContainerChanged();
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void gitmatica$markOpenContainerContentChanged(
            ClientboundContainerSetContentPacket packet,
            CallbackInfo callbackInfo)
    {
        VerifierInventoryOverlay.markOpenContainerChanged();
    }
}
