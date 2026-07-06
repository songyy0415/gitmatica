package me.zly2006.lvc.capture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import me.zly2006.lvc.LvcUserActionException;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcIntPosition;
import me.zly2006.lvc.semantic.LvcTrackedBlockCursor;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;

public final class LvcCaptureSession
{
    private final LvcSiteWorkPlan plan;
    private final LvcWorldReader worldReader;
    private final ObjectIdResolver objectIdResolver;
    private final boolean allowUnknownChunks;
    private final boolean computeFullHashes;
    private final boolean collectFurnaceXpCleanupCandidates;
    private final Map<String, String> fullHashes = new TreeMap<>();
    private final Map<String, String> trackedHashes = new TreeMap<>();
    private final Set<String> unknownChunks = new TreeSet<>();
    private final List<BlockPos> furnaceXpCleanupCandidates = new ArrayList<>();
    private int nextChunkIndex;
    private long fullHashContentBytes;
    private long storedObjectBytes;
    private long blockEntityReadAttempts;
    private long blockEntityRecords;

    public LvcCaptureSession(LvcSiteWorkPlan plan, LvcWorldReader worldReader, ObjectIdResolver objectIdResolver,
                             boolean allowUnknownChunks, boolean computeFullHashes)
    {
        this(plan, worldReader, objectIdResolver, allowUnknownChunks, computeFullHashes, false);
    }

    public LvcCaptureSession(LvcSiteWorkPlan plan, LvcWorldReader worldReader, ObjectIdResolver objectIdResolver,
                             boolean allowUnknownChunks, boolean computeFullHashes,
                             boolean collectFurnaceXpCleanupCandidates)
    {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.worldReader = Objects.requireNonNull(worldReader, "worldReader");
        this.objectIdResolver = objectIdResolver;
        this.allowUnknownChunks = allowUnknownChunks;
        this.computeFullHashes = computeFullHashes;
        this.collectFurnaceXpCleanupCandidates = collectFurnaceXpCleanupCandidates;

        if (computeFullHashes && objectIdResolver == null)
        {
            throw new IllegalArgumentException("LVC full capture requires an object id resolver");
        }
    }

    public boolean isComplete()
    {
        return this.nextChunkIndex >= this.plan.chunks().size();
    }

    public int processedChunks()
    {
        return this.nextChunkIndex;
    }

    public int totalChunks()
    {
        return this.plan.chunkCount();
    }

    public long fullHashContentBytes()
    {
        return this.fullHashContentBytes;
    }

    public long storedObjectBytes()
    {
        return this.storedObjectBytes;
    }

    public long blockEntityReadAttempts()
    {
        return this.blockEntityReadAttempts;
    }

    public long blockEntityRecords()
    {
        return this.blockEntityRecords;
    }

    public List<BlockPos> furnaceXpCleanupCandidates()
    {
        return List.copyOf(this.furnaceXpCleanupCandidates);
    }

    public LvcIntPosition origin()
    {
        return this.plan.origin();
    }

    public LvcSiteWorkPlan.ChunkWork currentChunkWork()
    {
        if (this.isComplete())
        {
            throw new IllegalStateException("LVC capture session is complete");
        }

        return this.plan.chunks().get(this.nextChunkIndex);
    }

    public void processNextChunk() throws IOException
    {
        if (this.isComplete())
        {
            return;
        }

        LvcSiteWorkPlan.ChunkWork work = this.plan.chunks().get(this.nextChunkIndex);
        this.captureChunk(work);
        this.nextChunkIndex++;
    }

    public LvcCaptureEngine.Result result()
    {
        if (!this.isComplete())
        {
            throw new IllegalStateException("LVC capture session is not complete");
        }

        return new LvcCaptureEngine.Result(this.fullHashes, this.trackedHashes, this.unknownChunks);
    }

    private void captureChunk(LvcSiteWorkPlan.ChunkWork work) throws IOException
    {
        String chunkKey = work.coordinate().key();
        BitSet mask = work.mask();
        List<String> blockStates = new ArrayList<>(mask.cardinality());
        List<LvcChunk.BlockEntityRecord> blockEntities = new ArrayList<>();
        List<LvcChunk.EntityRecord> entities = List.of();
        boolean unknown = false;

        for (LvcTrackedBlockCursor.Position tracked : LvcTrackedBlockCursor.positions(work.coordinate(), this.plan.origin(), mask,
                LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
        {
            LvcIntPosition worldPos = tracked.worldPos();

            if (!this.worldReader.canReadAt(worldPos))
            {
                if (!this.allowUnknownChunks)
                {
                    throw new LvcUserActionException(LvcUserActionException.Reason.TRACKED_CHUNK_UNREADABLE,
                            "LVC world reader cannot read an authoritative block state at " + worldPos);
                }

                unknown = true;
                break;
            }

            String blockState = this.worldReader.blockStateAt(worldPos);

            if (blockState == null || blockState.isBlank())
            {
                throw new IOException("LVC world reader returned a blank block state at " + worldPos);
            }

            blockStates.add(blockState);

            if (this.collectFurnaceXpCleanupCandidates && isFurnaceLikeBlockState(blockState))
            {
                this.furnaceXpCleanupCandidates.add(tracked.blockPos());
            }

            this.blockEntityReadAttempts++;
            byte[] blockEntityNbt = this.worldReader.blockEntityNbtAt(worldPos);

            if (blockEntityNbt != null)
            {
                blockEntities.add(new LvcChunk.BlockEntityRecord(tracked.maskIndex(), blockEntityNbt));
                this.blockEntityRecords++;
            }
        }

        if (unknown)
        {
            this.unknownChunks.add(chunkKey);
            return;
        }

        if (this.computeFullHashes)
        {
            entities = this.worldReader.entityRecordsInChunk(work.coordinate(), this.plan.origin(), mask,
                    LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE);
        }

        LvcChunk chunk = LvcChunk.fromTrackedContent(
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                mask,
                blockStates,
                blockEntities,
                entities
        );
        String trackedHash = LvcChunkStore.objectId(LvcChunkCodec.encodeTrackedContent(chunk));
        this.trackedHashes.put(chunkKey, trackedHash);

        if (this.computeFullHashes)
        {
            byte[] hashContentBytes = LvcChunkCodec.encodeHashContent(chunk);
            String objectId = LvcChunkStore.objectId(hashContentBytes);
            byte[] objectBytes = LvcChunkCodec.encodeStorageBytes(hashContentBytes);
            this.fullHashContentBytes += hashContentBytes.length;
            this.storedObjectBytes += objectBytes.length;
            String resolvedObjectId = this.objectIdResolver.resolve(objectId, objectBytes);

            if (!objectId.equals(resolvedObjectId))
            {
                throw new IOException("LVC object id resolver returned mismatched id: " + resolvedObjectId + " expected " + objectId);
            }

            this.fullHashes.put(chunkKey, objectId);
        }
    }

    private static boolean isFurnaceLikeBlockState(String blockState)
    {
        return blockState.equals("minecraft:furnace") || blockState.startsWith("minecraft:furnace[") ||
                blockState.equals("minecraft:blast_furnace") || blockState.startsWith("minecraft:blast_furnace[") ||
                blockState.equals("minecraft:smoker") || blockState.startsWith("minecraft:smoker[");
    }

    @FunctionalInterface
    public interface ObjectIdResolver
    {
        String resolve(String objectId, byte[] bytes) throws IOException;
    }
}
