package me.arnavpmr.lvc.mixin.placement;

import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.litematica.schematic.placement.PlacementManagerTask;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.arnavpmr.lvc.integration.litematica.placement.GitmaticaPlacementTaskContext;

@Mixin(PlacementManagerTask.class)
abstract class MixinPlacementManagerTask implements GitmaticaPlacementTaskContext
{
    @Shadow @Final private Supplier<WorldSchematic> worldSupplier;
    @Shadow @Final private int chunkX;
    @Shadow @Final private int chunkZ;
    @Shadow @Final private ChunkPos chunkPos;

    @Override
    public Supplier<WorldSchematic> gitmatica$worldSupplier()
    {
        return this.worldSupplier;
    }

    @Override
    public int gitmatica$chunkX()
    {
        return this.chunkX;
    }

    @Override
    public int gitmatica$chunkZ()
    {
        return this.chunkZ;
    }

    @Override
    public ChunkPos gitmatica$chunkPos()
    {
        return this.chunkPos;
    }
}
