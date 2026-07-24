package me.niicide.lvc.mixin.inventory;

import javax.annotation.Nullable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.render.GuiContext;
import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryOverlay;

@Mixin(AbstractContainerScreen.class)
abstract class MixinAbstractContainerScreenInventory extends Screen
{
    @Shadow @Nullable protected Slot hoveredSlot;

    protected MixinAbstractContainerScreenInventory(Component title)
    {
        super(title);
    }

    @ModifyExpressionValue(
            method = "extractSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;",
                    ordinal = 0))
    private ItemStack gitmatica$drawInventoryDiffSlot(
            ItemStack stack,
            @Local(argsOnly = true) GuiGraphicsExtractor graphics,
            @Local(argsOnly = true) Slot slot)
    {
        return VerifierInventoryOverlay.drawScreenStack(
                GuiContext.fromGuiGraphics(graphics),
                slot,
                stack);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void gitmatica$finishInventoryDiffSlot(CallbackInfo callbackInfo)
    {
        VerifierInventoryOverlay.finalizeDrawStack();
    }

    @ModifyExpressionValue(
            method = "extractTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;hasItem()Z"))
    private boolean gitmatica$exposeGhostItemToTooltipProviders(
            boolean hasStack,
            @Share("gitmatica$previousItem") LocalRef<ItemStack> previousItem)
    {
        if (this.hoveredSlot == null)
        {
            return hasStack;
        }

        ItemStack previous = this.hoveredSlot.getItem();

        if (!hasStack && VerifierInventoryOverlay.setSlotToExpectedItem(this.hoveredSlot))
        {
            hasStack = this.hoveredSlot.hasItem();

            if (hasStack)
            {
                previousItem.set(previous);
            }
            else
            {
                this.hoveredSlot.setByPlayer(previous);
            }
        }

        return hasStack;
    }

    @Inject(
            method = "extractTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;",
                    shift = At.Shift.AFTER))
    private void gitmatica$restoreGhostTooltipSlot(
            CallbackInfo callbackInfo,
            @Share("gitmatica$previousItem") LocalRef<ItemStack> previousItem)
    {
        ItemStack previous = previousItem.get();

        if (previous != null && this.hoveredSlot != null)
        {
            this.hoveredSlot.setByPlayer(previous);
        }
    }
}
