package me.niicide.lvc.integration.litematica.placement;

public interface GitmaticaPlacementDaemon
{
    void gitmatica$removeRebuildTasksFor(int chunkX, int chunkZ);

    void gitmatica$removeAllTasksFor(int chunkX, int chunkZ);
}
