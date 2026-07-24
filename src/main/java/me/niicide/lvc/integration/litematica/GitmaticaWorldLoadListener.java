package me.niicide.lvc.integration.litematica;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import me.niicide.lvc.gui.LvcInterruptedOperationPrompts;
import me.niicide.lvc.task.LvcTaskRegistry;

final class GitmaticaWorldLoadListener implements IWorldLoadListener
{
    @Override
    public void onWorldLoadPre(
            @Nullable ClientLevel worldBefore,
            @Nullable ClientLevel worldAfter,
            Minecraft minecraft)
    {
        if (worldBefore != null)
        {
            LvcTaskRegistry.abortActiveOperationForWorldUnload();
        }
    }

    @Override
    public void onWorldLoadPost(
            @Nullable ClientLevel worldBefore,
            @Nullable ClientLevel worldAfter,
            Minecraft minecraft)
    {
        if (worldAfter != null)
        {
            LvcInterruptedOperationPrompts.cancelInterruptedNonWorldOperationsOnWorldJoin(minecraft);
        }
    }
}
