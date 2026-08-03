package me.arnavpmr.lvc.mixin.access;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import me.arnavpmr.lvc.integration.litematica.placement.GitmaticaPlacementDaemon;

@Mixin(PlacementManagerDaemonHandler.class)
public interface PlacementManagerDaemonAccess extends GitmaticaPlacementDaemon
{
    @Override
    @Invoker("removeRebuildTasksFor")
    void gitmatica$removeRebuildTasksFor(int chunkX, int chunkZ);

    @Override
    @Invoker("removeAllTasksFor")
    void gitmatica$removeAllTasksFor(int chunkX, int chunkZ);
}
