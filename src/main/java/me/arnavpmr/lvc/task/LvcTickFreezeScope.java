package me.arnavpmr.lvc.task;

import java.io.IOException;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.model.LvcIntPosition;
import me.arnavpmr.lvc.model.LvcManifest;

final class LvcTickFreezeScope implements AutoCloseable
{
    private final ServerLevel world;
    private final LvcOperationHandle handle;
    private final ServerTickRateManager manager;
    private final boolean wasFrozen;
    private final int frozenTicksToRestore;
    private boolean scheduledActivityCleared;
    private boolean closed;

    private LvcTickFreezeScope(ServerLevel world, LvcOperationHandle handle)
    {
        this.world = world;
        this.handle = handle;
        this.manager = world.getServer().tickRateManager();
        this.wasFrozen = this.manager.isFrozen();
        this.frozenTicksToRestore = this.manager.frozenTicksToRun();

        this.manager.setFrozen(true);

        if (this.manager.isSteppingForward())
        {
            this.manager.stopStepping();
        }

        LvcDiagnostics.debug(handle,
                "semantic discard acquired global tick freeze previousFrozen={} previousStepTicks={}",
                this.wasFrozen, this.frozenTicksToRestore);
    }

    static LvcTickFreezeScope acquire(ServerLevel world, LvcOperationHandle handle)
    {
        return new LvcTickFreezeScope(world, handle);
    }

    void clearScheduledActivity(List<LvcManifest.Region> regions, LvcIntPosition origin) throws IOException
    {
        if (this.scheduledActivityCleared)
        {
            return;
        }

        int blockTicksBefore = this.world.getBlockTicks().count();
        int fluidTicksBefore = this.world.getFluidTicks().count();
        int regionCount = 0;

        try
        {
            for (LvcManifest.Region region : regions)
            {
                BoundingBox bounds = trackedWorldBounds(region, origin);
                this.world.getBlockTicks().clearArea(bounds);
                this.world.getFluidTicks().clearArea(bounds);
                this.world.clearBlockEvents(bounds);
                regionCount++;
            }
        }
        catch (RuntimeException e)
        {
            throw new IOException("Failed to clear scheduled activity inside LVC tracking bounds", e);
        }

        this.scheduledActivityCleared = true;
        LvcDiagnostics.debug(this.handle,
                "semantic discard cleared scheduled activity trackedRegions={} removedBlockTicks={} removedFluidTicks={} blockEventsCleared=true",
                regionCount,
                Math.max(0, blockTicksBefore - this.world.getBlockTicks().count()),
                Math.max(0, fluidTicksBefore - this.world.getFluidTicks().count()));
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;
        MinecraftServer server = this.world.getServer();
        Runnable restore = () ->
        {
            if (this.manager.isSteppingForward())
            {
                this.manager.stopStepping();
            }

            this.manager.setFrozen(this.wasFrozen);

            if (this.wasFrozen && this.frozenTicksToRestore > 0)
            {
                this.manager.stepGameIfPaused(this.frozenTicksToRestore);
            }

            LvcDiagnostics.debug(this.handle,
                    "semantic discard restored global tick state frozen={} stepTicks={}",
                    this.wasFrozen, this.wasFrozen ? this.frozenTicksToRestore : 0);
        };

        try
        {
            if (server.isSameThread())
            {
                restore.run();
            }
            else if (server.isRunning())
            {
                server.executeIfPossible(restore);
            }
            else
            {
                LvcDiagnostics.debug(this.handle,
                        "semantic discard skipped tick-state restore because the integrated server is stopping");
            }
        }
        catch (RuntimeException e)
        {
            LvcDiagnostics.warn("semantic discard failed to restore global tick state operation={} error='{}'",
                    this.handle.id(), e.getMessage());
        }
    }

    private static BoundingBox trackedWorldBounds(LvcManifest.Region region, LvcIntPosition origin) throws IOException
    {
        LvcIntPosition min = LvcIntPosition.fromList(region.min());
        LvcIntPosition size = LvcIntPosition.fromList(region.size());

        try
        {
            int minX = Math.addExact(origin.x(), min.x());
            int minY = Math.addExact(origin.y(), min.y());
            int minZ = Math.addExact(origin.z(), min.z());
            int maxX = Math.addExact(minX, Math.subtractExact(size.x(), 1));
            int maxY = Math.addExact(minY, Math.subtractExact(size.y(), 1));
            int maxZ = Math.addExact(minZ, Math.subtractExact(size.z(), 1));
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
        catch (ArithmeticException e)
        {
            throw new IOException("LVC tracking bounds overflow for region " + region.id(), e);
        }
    }
}
