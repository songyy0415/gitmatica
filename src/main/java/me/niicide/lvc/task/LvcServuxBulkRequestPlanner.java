package me.niicide.lvc.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import fi.dy.masa.litematica.data.EntityDataManager;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.capture.LvcCaptureSession;
import me.niicide.lvc.capture.LvcSiteWorkPlan;
import me.niicide.lvc.capture.LvcServuxBulkEntityCache;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;

final class LvcServuxBulkRequestPlanner
{
    private static final int MAX_IN_FLIGHT_COLUMNS = 32;
    private static final int MAX_REQUESTS_PER_STEP = 8;

    private final LvcIntPosition origin;
    private final Map<ChunkPos, YRange> rangesByColumn = new LinkedHashMap<>();
    private final List<ChunkPos> columnsInOrder = new ArrayList<>();
    private final Set<ChunkPos> completedColumns = new HashSet<>();
    private final Set<ChunkPos> requestedColumns = new HashSet<>();
    private final Set<ChunkPos> inFlightColumns = new HashSet<>();
    private int nextRequestIndex;

    private LvcServuxBulkRequestPlanner(LvcSiteWorkPlan plan)
    {
        this.origin = plan.origin();

        for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
        {
            int minY = this.chunkMinY(work.coordinate());
            int maxY = minY + LvcChunk.DEFAULT_SIZE - 1;

            for (ChunkPos chunkPos : this.realColumnsFor(work.coordinate()))
            {
                if (!this.rangesByColumn.containsKey(chunkPos))
                {
                    this.columnsInOrder.add(chunkPos);
                }

                this.rangesByColumn.merge(chunkPos, new YRange(minY, maxY), YRange::merge);
            }
        }
    }

    static LvcServuxBulkRequestPlanner create(LvcSiteWorkPlan plan)
    {
        return new LvcServuxBulkRequestPlanner(Objects.requireNonNull(plan, "plan"));
    }

    int totalColumns()
    {
        return this.rangesByColumn.size();
    }

    boolean ensureReadyForCurrentChunk(LvcCaptureSession session, LvcOperationHandle handle, String phaseName)
    {
        LvcSiteWorkPlan.ChunkWork work = session.currentChunkWork();
        EntityDataManager entityDataManager = EntityDataManager.getInstance();

        this.consumeCompletedColumns(entityDataManager, handle, phaseName);
        this.requestMoreColumns(entityDataManager, handle, phaseName);

        for (ChunkPos column : this.realColumnsFor(work.coordinate()))
        {
            if (!this.completedColumns.contains(column))
            {
                return false;
            }
        }

        return true;
    }

    private void consumeCompletedColumns(EntityDataManager entityDataManager, LvcOperationHandle handle, String phaseName)
    {
        if (this.inFlightColumns.isEmpty())
        {
            return;
        }

        List<ChunkPos> completed = new ArrayList<>();

        for (ChunkPos column : this.inFlightColumns)
        {
            if (entityDataManager.hasCompletedChunk(column))
            {
                completed.add(column);
            }
        }

        int completedWithoutReply = 0;

        for (ChunkPos column : completed)
        {
            if (!LvcServuxBulkEntityCache.hasReply(column))
            {
                completedWithoutReply++;
            }

            entityDataManager.markCompletedChunkDirty(column);
            this.inFlightColumns.remove(column);
            this.completedColumns.add(column);
        }

        if (!completed.isEmpty())
        {
            LvcDiagnostics.debug(handle, "{} Servux bulk data completed columns={} completedWithoutReply={} completedColumns={}/{} inFlight={}",
                    phaseName, completed.size(), completedWithoutReply,
                    this.completedColumns.size(), this.totalColumns(), this.inFlightColumns.size());
        }
    }

    private void requestMoreColumns(EntityDataManager entityDataManager, LvcOperationHandle handle, String phaseName)
    {
        int requestedThisStep = 0;

        while (this.inFlightColumns.size() < MAX_IN_FLIGHT_COLUMNS &&
               requestedThisStep < MAX_REQUESTS_PER_STEP &&
               this.nextRequestIndex < this.columnsInOrder.size())
        {
            ChunkPos column = this.columnsInOrder.get(this.nextRequestIndex++);

            if (this.requestedColumns.contains(column) || this.completedColumns.contains(column))
            {
                continue;
            }

            YRange range = Objects.requireNonNull(this.rangesByColumn.get(column), "Servux Y range missing for " + column);
            LvcServuxBulkEntityCache.beginRequest(column, range.minY(), range.maxY());
            entityDataManager.requestServuxBulkEntityData(column, range.minY(), range.maxY());
            this.requestedColumns.add(column);
            this.inFlightColumns.add(column);
            requestedThisStep++;
        }

        if (requestedThisStep > 0)
        {
            LvcDiagnostics.debug(handle, "{} requested Servux bulk data columns={} completedColumns={}/{} inFlight={}",
                    phaseName, requestedThisStep, this.completedColumns.size(), this.totalColumns(), this.inFlightColumns.size());
        }
    }

    private List<ChunkPos> realColumnsFor(LvcChunkCoordinate coordinate)
    {
        int minX = this.origin.x() + coordinate.x() * LvcChunk.DEFAULT_SIZE;
        int minZ = this.origin.z() + coordinate.z() * LvcChunk.DEFAULT_SIZE;
        int maxX = minX + LvcChunk.DEFAULT_SIZE - 1;
        int maxZ = minZ + LvcChunk.DEFAULT_SIZE - 1;
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

    private int chunkMinY(LvcChunkCoordinate coordinate)
    {
        return this.origin.y() + coordinate.y() * LvcChunk.DEFAULT_SIZE;
    }

    private record YRange(int minY, int maxY)
    {
        private static YRange merge(YRange first, YRange second)
        {
            return new YRange(Math.min(first.minY, second.minY), Math.max(first.maxY, second.maxY));
        }
    }
}
