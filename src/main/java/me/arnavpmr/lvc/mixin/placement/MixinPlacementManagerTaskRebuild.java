package me.arnavpmr.lvc.mixin.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerTaskRebuild;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart;
import fi.dy.masa.litematica.util.WorldPlacingUtils;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.ProtoChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.util.WorldUtils;
import me.arnavpmr.lvc.integration.litematica.placement.GitmaticaPlacementDaemon;
import me.arnavpmr.lvc.integration.litematica.placement.GitmaticaPlacementTaskContext;

/**
 * Prevents a delayed rebuild from publishing a chunk assembled from placements
 * that were removed or replaced while the task was running.
 */
@Mixin(PlacementManagerTaskRebuild.class)
abstract class MixinPlacementManagerTaskRebuild
{
    /**
     * @author Gitmatica
     * @reason The final placement identity check must surround the atomic
     * schematic-world chunk replacement.
     */
    @Overwrite
    protected Runnable buildTask()
    {
        return () ->
        {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            WorldSchematic schematicWorld = this.gitmatica$task().gitmatica$worldSupplier().get();
            ClientLevel clientWorld = Minecraft.getInstance().level;

            if (clientWorld == null)
            {
                PlacementManagerDaemonHandler.INSTANCE.clearAllTasks();
                return;
            }

            if (this.gitmatica$activePlacements(manager).isEmpty())
            {
                this.gitmatica$unloadStaleChunk(schematicWorld);
                return;
            }

            if (Configs.Generic.LOAD_ENTIRE_SCHEMATICS.getBooleanValue() ||
                WorldUtils.isClientChunkLoaded(clientWorld, this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ()))
            {
                if (schematicWorld.getChunkSource().hasChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ()))
                {
                    schematicWorld.unloadEntitiesByChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
                    schematicWorld.getChunkSource().unloadChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
                    manager.setVisibleSubChunksNeedsUpdate();
                }

                schematicWorld.getChunkSource().loadChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
                manager.setVisibleSubChunksNeedsUpdate();
            }

            if (!schematicWorld.getChunkSource().hasChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ()))
            {
                return;
            }

            ProtoChunkSchematic protoChunk =
                    new ProtoChunkSchematic(new ChunkSchematic(schematicWorld, this.gitmatica$task().gitmatica$chunkPos()));
            List<SchematicPlacement> placements = this.gitmatica$activePlacements(manager);
            protoChunk.setState(ChunkSchematicState.PROTO);

            if (!placements.isEmpty())
            {
                for (SchematicPlacement placement : placements)
                {
                    WorldPlacingUtils.placeToProtoChunk(protoChunk, this.gitmatica$task().gitmatica$chunkPos(), placement);
                }

                List<SchematicPlacement> currentPlacements = this.gitmatica$activePlacements(manager);

                if (!this.gitmatica$samePlacements(placements, currentPlacements))
                {
                    protoChunk.clear();

                    if (currentPlacements.isEmpty())
                    {
                        this.gitmatica$unloadStaleChunk(schematicWorld);
                    }
                    else
                    {
                        manager.markChunkForRebuild(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
                    }

                    return;
                }

                schematicWorld.unloadEntitiesByChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
                schematicWorld.getChunkSource().replaceChunk(
                        this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ(), protoChunk.getWrapped());
                protoChunk.spawnAllEntitiesNow(schematicWorld);
            }

            protoChunk.clear();
            ((GitmaticaPlacementDaemon) PlacementManagerDaemonHandler.INSTANCE)
                    .gitmatica$removeRebuildTasksFor(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
            schematicWorld.scheduleChunkRenders(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
            manager.setVisibleSubChunksNeedsUpdate();
        };
    }

    @Unique
    private GitmaticaPlacementTaskContext gitmatica$task()
    {
        return (GitmaticaPlacementTaskContext) this;
    }

    @Unique
    private List<SchematicPlacement> gitmatica$activePlacements(
            SchematicPlacementManager manager)
    {
        Set<SchematicPlacement> touching = new LinkedHashSet<>();

        for (PlacementPart part : manager.getPlacementPartsInChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ()))
        {
            touching.add(part.getPlacement());
        }

        if (touching.isEmpty())
        {
            return List.of();
        }

        List<SchematicPlacement> allPlacements = manager.getAllSchematicsPlacements();
        List<SchematicPlacement> active = new ArrayList<>(touching.size());

        for (SchematicPlacement placement : touching)
        {
            if (placement.isEnabled() && allPlacements.contains(placement))
            {
                active.add(placement);
            }
        }

        return active;
    }

    @Unique
    private void gitmatica$unloadStaleChunk(WorldSchematic schematicWorld)
    {
        ((GitmaticaPlacementDaemon) PlacementManagerDaemonHandler.INSTANCE)
                .gitmatica$removeAllTasksFor(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
        schematicWorld.unloadEntitiesByChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
        schematicWorld.getChunkSource().unloadChunk(this.gitmatica$task().gitmatica$chunkX(), this.gitmatica$task().gitmatica$chunkZ());
    }

    @Unique
    private boolean gitmatica$samePlacements(
            Collection<SchematicPlacement> expected,
            Collection<SchematicPlacement> current)
    {
        return expected.size() == current.size() &&
               expected.containsAll(current) &&
               current.containsAll(expected);
    }
}
