package me.niicide.lvc.task;

import java.util.Collection;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkCommand;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import fi.dy.masa.litematica.world.ChunkSchematic;

final class LvcSparseCommandPasteTask extends TaskPasteSchematicPerChunkCommand implements LvcWorldTask
{
    private final LvcTaskEpoch taskEpoch = LvcTaskEpoch.capture();

    LvcSparseCommandPasteTask(Collection<SchematicPlacement> placements, LayerRange range, boolean changedBlocksOnly)
    {
        super(placements, range, changedBlocksOnly);
    }

    @Override
    public boolean shouldRemove()
    {
        return !this.taskEpoch.isCurrent() || super.shouldRemove();
    }

    @Override
    public void setCompletionListener(@Nullable ICompletionListener listener)
    {
        super.setCompletionListener(this.taskEpoch.guard(listener));
    }

    @Override
    protected void pasteBlock(BlockPos pos, LevelChunk schematicChunk, ChunkAccess clientChunk, boolean ignoreLimit)
    {
        if (schematicChunk.getBlockState(pos).is(Blocks.STRUCTURE_VOID))
        {
            return;
        }

        super.pasteBlock(pos, schematicChunk, clientChunk, ignoreLimit);
    }

    @Override
    protected boolean shouldSetBlock(BlockState stateSchematic, BlockState stateClient)
    {
        return !stateSchematic.is(Blocks.STRUCTURE_VOID) && super.shouldSetBlock(stateSchematic, stateClient);
    }

    @Override
    protected void generateStrips(int[][][] workArr, Direction stripDirection, IntBoundingBox box,
                                  ChunkSchematic chunk, boolean ignoreBeFromFill)
    {
        boolean ignoreBeEntirely = Configs.Generic.PASTE_IGNORE_BE_ENTIRELY.getBooleanValue();
        BlockPos.MutableBlockPos mutablePos = this.mutablePos;
        ReplaceBehavior replace = this.replace;
        final int startX = box.minX() & 0xF;
        final int startZ = box.minZ() & 0xF;
        final int endX = box.maxX() & 0xF;
        final int endZ = box.maxZ() & 0xF;
        final int worldMinY = chunk.getMinY();

        for (int y = box.minY(); y <= box.maxY(); ++y)
        {
            for (int z = startZ; z <= endZ; ++z)
            {
                for (int x = startX; x <= endX; ++x)
                {
                    mutablePos.set(x, y, z);
                    BlockState state = chunk.getBlockState(mutablePos);

                    if (state.is(Blocks.STRUCTURE_VOID))
                    {
                        workArr[x][y - worldMinY][z] = 0;
                        continue;
                    }

                    if (state.isAir() == false || replace == ReplaceBehavior.ALL)
                    {
                        if (state.hasBlockEntity())
                        {
                            if (ignoreBeFromFill)
                            {
                                workArr[x][y - worldMinY][z] = 1;
                                continue;
                            }
                            else if (ignoreBeEntirely)
                            {
                                workArr[x][y - worldMinY][z] = 0;
                                continue;
                            }
                        }

                        int length = this.getBlockStripLength(mutablePos, stripDirection, endX - x + 1, state, chunk);
                        workArr[x][y - worldMinY][z] = length;
                        x += length - 1;
                    }
                    else
                    {
                        workArr[x][y - worldMinY][z] = 0;
                    }
                }
            }
        }
    }

    @Override
    protected int getBlockStripLength(BlockPos.MutableBlockPos pos, Direction direction, int maxLength,
                                      BlockState firstState, ChunkAccess chunk)
    {
        int length = 1;

        while (length < maxLength)
        {
            pos.move(direction);
            BlockState state = chunk.getBlockState(pos);

            if (state.is(Blocks.STRUCTURE_VOID) || state != firstState)
            {
                break;
            }

            ++length;
        }

        return length;
    }
}
