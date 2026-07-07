package me.niicide.lvc.capture;

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
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.util.EntityUtils;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.storage.LvcCanonicalNbt;
import me.niicide.lvc.util.LvcEntityNbt;

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
        List<ChunkPos> columns = realColumnsFor(coordinate, origin, sizeX, sizeZ);

        if (columns.stream().allMatch(LvcServuxBulkEntityCache::hasReply))
        {
            return this.entityRecordsFromServuxBulk(coordinate, origin, mask, sizeX, sizeY, sizeZ, columns);
        }

        return this.entityRecordsFromClientWorld(coordinate, origin, mask, sizeX, sizeY, sizeZ);
    }

    private List<LvcChunk.EntityRecord> entityRecordsFromServuxBulk(LvcChunkCoordinate coordinate, LvcIntPosition origin,
                                                                    BitSet mask, int sizeX, int sizeY, int sizeZ,
                                                                    List<ChunkPos> columns) throws IOException
    {
        LvcIntPosition chunkMinProject = new LvcIntPosition(coordinate.x() * sizeX, coordinate.y() * sizeY, coordinate.z() * sizeZ);
        List<LvcChunk.EntityRecord> records = new ArrayList<>();
        Set<Integer> capturedRuntimeIds = new HashSet<>();
        Set<UUID> passengerUuids = this.nestedPassengerUuids(columns);
        int skippedRuntimeDuplicates = 0;
        int skippedPassengerEntries = 0;

        for (ChunkPos column : columns)
        {
            for (LvcServuxBulkEntityCache.Entry entry : LvcServuxBulkEntityCache.entriesFor(column))
            {
                CompoundTag entityNbt = entry.nbt();
                int runtimeId = entityNbt.getIntOr("entityId", Integer.MIN_VALUE);
                UUID entityUuid = this.entityUuid(entityNbt);

                if (runtimeId != Integer.MIN_VALUE && !capturedRuntimeIds.add(runtimeId))
                {
                    skippedRuntimeDuplicates++;
                    continue;
                }

                if (entityUuid != null && passengerUuids.contains(entityUuid))
                {
                    skippedPassengerEntries++;
                    continue;
                }

                if (entityNbt.contains("RootVehicle") || !entityNbt.contains("Pos"))
                {
                    continue;
                }

                Vec3 projectPos = servuxProjectPosition(entityNbt, entry.chunkPos(), entry.minY(), origin);
                int localX = floor(projectPos.x) - chunkMinProject.x();
                int localY = floor(projectPos.y) - chunkMinProject.y();
                int localZ = floor(projectPos.z) - chunkMinProject.z();

                if (localX < 0 || localX >= sizeX || localY < 0 || localY >= sizeY || localZ < 0 || localZ >= sizeZ)
                {
                    continue;
                }

                int maskIndex = LvcCapturePlanner.index(localX, localY, localZ, sizeX, sizeY);

                if (!mask.get(maskIndex))
                {
                    continue;
                }

                byte[] canonicalNbt = LvcEntityNbt.captureServuxBulkProjectRelative(entityNbt, entry.chunkPos(), entry.minY(), origin);

                if (canonicalNbt != null)
                {
                    records.add(new LvcChunk.EntityRecord(canonicalNbt));
                }
            }
        }

        LvcDiagnostics.debug("LvcServuxWorldReader: captured Servux bulk entities chunk={} columns={} records={} skippedRuntimeDuplicates={} skippedPassengerEntries={}",
                coordinate.key(), columns.size(), records.size(), skippedRuntimeDuplicates, skippedPassengerEntries);
        return records;
    }

    private Set<UUID> nestedPassengerUuids(List<ChunkPos> columns)
    {
        Set<UUID> uuids = new HashSet<>();

        for (ChunkPos column : columns)
        {
            for (LvcServuxBulkEntityCache.Entry entry : LvcServuxBulkEntityCache.entriesFor(column))
            {
                this.collectNestedPassengerUuids(entry.nbt(), uuids);
            }
        }

        return uuids;
    }

    private void collectNestedPassengerUuids(CompoundTag entityNbt, Set<UUID> uuids)
    {
        if (!entityNbt.contains("Passengers"))
        {
            return;
        }

        ListTag passengers = entityNbt.getListOrEmpty("Passengers");

        for (int i = 0; i < passengers.size(); i++)
        {
            CompoundTag passenger = passengers.getCompoundOrEmpty(i);
            UUID passengerUuid = this.entityUuid(passenger);

            if (passengerUuid != null)
            {
                uuids.add(passengerUuid);
            }

            this.collectNestedPassengerUuids(passenger, uuids);
        }
    }

    @Nullable
    private UUID entityUuid(CompoundTag entityNbt)
    {
        if (!entityNbt.contains("UUID"))
        {
            return null;
        }

        return entityNbt.read("UUID", UUIDUtil.AUTHLIB_CODEC,
                this.world.registryAccess().createSerializationContext(NbtOps.INSTANCE)).orElse(null);
    }

    private List<LvcChunk.EntityRecord> entityRecordsFromClientWorld(LvcChunkCoordinate coordinate, LvcIntPosition origin,
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

    private static List<ChunkPos> realColumnsFor(LvcChunkCoordinate coordinate, LvcIntPosition origin, int sizeX, int sizeZ)
    {
        int minX = origin.x() + coordinate.x() * sizeX;
        int minZ = origin.z() + coordinate.z() * sizeZ;
        int maxX = minX + sizeX - 1;
        int maxZ = minZ + sizeZ - 1;
        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        List<ChunkPos> columns = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                columns.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        return columns;
    }

    private static Vec3 servuxProjectPosition(CompoundTag entityNbt, ChunkPos chunkPos, int minY, LvcIntPosition origin)
    {
        Vec3 requestRelativePos = fi.dy.masa.malilib.util.nbt.NbtUtils.getVec3dCodec(entityNbt, "Pos");
        return new Vec3(
                requestRelativePos.x + chunkPos.getMinBlockX() - origin.x(),
                requestRelativePos.y + minY - origin.y(),
                requestRelativePos.z + chunkPos.getMinBlockZ() - origin.z()
        );
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
