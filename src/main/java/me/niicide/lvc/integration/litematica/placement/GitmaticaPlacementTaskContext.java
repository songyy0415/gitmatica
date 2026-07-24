package me.niicide.lvc.integration.litematica.placement;

import java.util.function.Supplier;

import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.litematica.world.WorldSchematic;

public interface GitmaticaPlacementTaskContext
{
    Supplier<WorldSchematic> gitmatica$worldSupplier();

    int gitmatica$chunkX();

    int gitmatica$chunkZ();

    ChunkPos gitmatica$chunkPos();
}
