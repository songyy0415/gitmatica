package me.zly2006.lvc.capture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.util.EntityUtils;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcChunkCoordinate;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.storage.LvcCanonicalNbt;
import me.zly2006.lvc.util.LvcEntityNbt;

public final class LvcServuxWorldReader implements LvcWorldReader
{
    private final LvcBlockStateOnlyWorldReader blockStateReader;
    private final Level world;

    public LvcServuxWorldReader(Level world)
    {
        this.world = Objects.requireNonNull(world, "world");
        this.blockStateReader = new LvcBlockStateOnlyWorldReader(world);
    }

    @Override
    public boolean canReadAt(LvcIntPosition worldPos)
    {
        return this.blockStateReader.canReadAt(worldPos);
    }

    @Override
    public String blockStateAt(LvcIntPosition worldPos) throws IOException
    {
        return this.blockStateReader.blockStateAt(worldPos);
    }

    @Override
    @Nullable
    public byte[] blockEntityNbtAt(LvcIntPosition worldPos) throws IOException
    {
        BlockPos pos = new BlockPos(worldPos.x(), worldPos.y(), worldPos.z());
        CompoundTag cached = EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(pos);

        if (cached != null && !cached.isEmpty())
        {
            return LvcCanonicalNbt.encodeBlockEntity(cached);
        }

        return null;
    }

    @Override
    public List<LvcChunk.EntityRecord> entityRecordsInChunk(LvcChunkCoordinate coordinate, LvcIntPosition origin,
                                                            BitSet mask, int sizeX, int sizeY, int sizeZ) throws IOException
    {
        LvcIntPosition chunkMinProject = new LvcIntPosition(coordinate.x() * sizeX, coordinate.y() * sizeY, coordinate.z() * sizeZ);
        LvcIntPosition chunkMinWorld = origin.offset(chunkMinProject);
        AABB bounds = new AABB(chunkMinWorld.x(), chunkMinWorld.y(), chunkMinWorld.z(),
                chunkMinWorld.x() + sizeX, chunkMinWorld.y() + sizeY, chunkMinWorld.z() + sizeZ);
        List<Entity> entities = this.world.getEntities((Entity) null, bounds, EntityUtils.NOT_PLAYER);
        List<LvcChunk.EntityRecord> records = new ArrayList<>();
        Set<UUID> captured = new HashSet<>();

        for (Entity entity : entities)
        {
            if (entity.isPassenger() || !captured.add(entity.getUUID()))
            {
                continue;
            }

            int localX = floor(entity.getX() - origin.x()) - chunkMinProject.x();
            int localY = floor(entity.getY() - origin.y()) - chunkMinProject.y();
            int localZ = floor(entity.getZ() - origin.z()) - chunkMinProject.z();

            if (localX < 0 || localX >= sizeX || localY < 0 || localY >= sizeY || localZ < 0 || localZ >= sizeZ)
            {
                continue;
            }

            int maskIndex = LvcCapturePlanner.index(localX, localY, localZ, sizeX, sizeY);

            if (!mask.get(maskIndex))
            {
                continue;
            }

            CompoundTag cached = EntityDataManager.getInstance().getCache().getEntityNbtFromCache(entity.getId());
            byte[] canonicalNbt = cached != null && !cached.isEmpty() ?
                    LvcEntityNbt.captureProjectRelative(cached, entity, this.world, origin) :
                    LvcEntityNbt.captureProjectRelative(entity, this.world, origin);

            if (canonicalNbt != null)
            {
                records.add(new LvcChunk.EntityRecord(canonicalNbt));
            }
        }

        return records;
    }

    public static ChunkPos chunkPosFor(LvcSiteWorkPlan.ChunkWork work, LvcIntPosition origin)
    {
        LvcIntPosition project = new LvcIntPosition(work.coordinate().x() * LvcChunk.DEFAULT_SIZE, 0,
                work.coordinate().z() * LvcChunk.DEFAULT_SIZE);
        LvcIntPosition worldPos = origin.offset(project);
        return new ChunkPos(SectionPos.blockToSectionCoord(worldPos.x()), SectionPos.blockToSectionCoord(worldPos.z()));
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }
}
