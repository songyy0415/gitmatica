package me.niicide.lvc.task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import me.niicide.lvc.capture.LvcSiteWorkPlan;
import me.niicide.lvc.capture.LvcWorldReader;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.semantic.LvcTrackedBlockCursor;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.world.LvcWorldBackend;

public final class LvcRemoteSparseTargetPlanner
{
    private final LvcWorldBackend backend;
    private final LvcWorldReader reader;
    private final LvcManifest.Site site;
    private final List<BlockPos> furnaceXpCleanupCandidates = new ArrayList<>();
    private final List<CommandMutation> commandMutations = new ArrayList<>();
    private final Set<String> affectedRegionNames = new HashSet<>();
    private final Set<LvcChunkCoordinate> retiredNonAirChunks = new HashSet<>();
    private int scannedBlocks;
    private int scannedRetiredBlocks;
    private int retiredNonAirBlocks;
    private int stateMismatches;
    private int blockEntityMismatches;
    private int ignoredBlockEntityTargets;

    public LvcRemoteSparseTargetPlanner(LvcWorldBackend backend, LvcWorldReader reader, LvcManifest.Site site)
    {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.site = Objects.requireNonNull(site, "site");
    }

    public boolean include(LvcSemanticSchematicBuilder.TargetBlock block, BlockState targetState) throws IOException
    {
        Objects.requireNonNull(block, "block");

        if (!this.reader.canReadAt(block.worldPos()))
        {
            throw new IOException("LVC remote sparse target diff cannot read tracked block at " + block.blockPos());
        }

        this.scannedBlocks++;
        String currentBlockState = LvcChunkCodec.canonicalTrackedBlockState(this.reader.blockStateAt(block.worldPos()));
        String targetBlockState = LvcChunkCodec.canonicalTrackedBlockState(block.blockState());

        if (!Objects.equals(currentBlockState, targetBlockState))
        {
            this.stateMismatches++;
            this.addCommandMutationIfNeeded(block.blockPos(), targetState);
            this.addFurnaceXpCleanupCandidateIfNeeded(currentBlockState, block.blockPos());
            this.addAffectedRegions(block.projectPos());
            return true;
        }

        byte[] targetBlockEntity = block.blockEntityBytes();

        if (this.backend == LvcWorldBackend.COMMANDS)
        {
            if (targetBlockEntity != null)
            {
                this.ignoredBlockEntityTargets++;
            }

            return false;
        }

        if (!blockEntityMatchesTrackedPayload(block.worldPos(), targetBlockEntity))
        {
            this.blockEntityMismatches++;
            this.addFurnaceXpCleanupCandidateIfNeeded(currentBlockState, block.blockPos());
            this.addAffectedRegions(block.projectPos());
            return true;
        }

        return false;
    }

    public void scanRetiredCoverageChunk(LvcSiteWorkPlan.ChunkWork work, LvcIntPosition origin) throws IOException
    {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(origin, "origin");

        for (LvcTrackedBlockCursor.Position position : LvcTrackedBlockCursor.positions(
                work.coordinate(), origin, work.mask(),
                LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
        {
            if (!this.reader.canReadAt(position.worldPos()))
            {
                throw new IOException("LVC remote retired coverage scan cannot read block at " +
                        position.blockPos());
            }

            this.scannedRetiredBlocks++;
            String currentBlockState = LvcChunkCodec.canonicalTrackedBlockState(
                    this.reader.blockStateAt(position.worldPos()));

            if (!"minecraft:air".equals(currentBlockState))
            {
                this.retiredNonAirBlocks++;
                this.retiredNonAirChunks.add(work.coordinate());
                this.addFurnaceXpCleanupCandidateIfNeeded(currentBlockState, position.blockPos());
            }
        }
    }

    private boolean blockEntityMatchesTrackedPayload(LvcIntPosition worldPos, @Nullable byte[] targetBlockEntity) throws IOException
    {
        byte[] targetTrackedNbt = targetBlockEntity == null ? null :
                LvcChunkCodec.encodeTrackedBlockEntityContent(targetBlockEntity);
        byte[] currentBlockEntity = this.reader.blockEntityNbtAt(worldPos);
        byte[] currentTrackedNbt = currentBlockEntity == null ? null :
                LvcChunkCodec.encodeTrackedBlockEntityContent(currentBlockEntity);

        return Arrays.equals(currentTrackedNbt, targetTrackedNbt);
    }

    public int scannedBlocks()
    {
        return this.scannedBlocks;
    }

    public int stateMismatches()
    {
        return this.stateMismatches;
    }

    public int scannedRetiredBlocks()
    {
        return this.scannedRetiredBlocks;
    }

    public int retiredNonAirBlocks()
    {
        return this.retiredNonAirBlocks;
    }

    public Set<LvcChunkCoordinate> retiredNonAirChunks()
    {
        return Collections.unmodifiableSet(this.retiredNonAirChunks);
    }

    public int blockEntityMismatches()
    {
        return this.blockEntityMismatches;
    }

    public int ignoredBlockEntityTargets()
    {
        return this.ignoredBlockEntityTargets;
    }

    public List<BlockPos> furnaceXpCleanupCandidates()
    {
        return Collections.unmodifiableList(this.furnaceXpCleanupCandidates);
    }

    public Set<String> affectedRegionNames()
    {
        return Collections.unmodifiableSet(this.affectedRegionNames);
    }

    public List<CommandMutation> commandMutations()
    {
        List<CommandMutation> mutations = new ArrayList<>(this.commandMutations);
        mutations.sort(Comparator
                .comparingInt((CommandMutation mutation) -> mutation.pos().getX())
                .thenComparingInt(mutation -> mutation.pos().getY())
                .thenComparingInt(mutation -> mutation.pos().getZ()));
        return Collections.unmodifiableList(mutations);
    }

    private void addCommandMutationIfNeeded(BlockPos pos, BlockState targetState)
    {
        if (this.backend == LvcWorldBackend.COMMANDS)
        {
            this.commandMutations.add(new CommandMutation(pos.immutable(), targetState));
        }
    }

    private void addFurnaceXpCleanupCandidateIfNeeded(String currentBlockState, BlockPos pos)
    {
        if (isFurnaceLikeBlockState(currentBlockState))
        {
            this.furnaceXpCleanupCandidates.add(pos);
        }
    }

    private void addAffectedRegions(LvcIntPosition projectPos)
    {
        for (LvcManifest.Region region : this.site.regions())
        {
            LvcIntPosition min = LvcIntPosition.fromList(region.min());
            LvcIntPosition size = LvcIntPosition.fromList(region.size());

            if (projectPos.x() >= min.x() && projectPos.x() < min.x() + size.x() &&
                    projectPos.y() >= min.y() && projectPos.y() < min.y() + size.y() &&
                    projectPos.z() >= min.z() && projectPos.z() < min.z() + size.z())
            {
                this.affectedRegionNames.add(region.name());
            }
        }
    }

    private static boolean isFurnaceLikeBlockState(String blockState)
    {
        return blockState.equals("minecraft:furnace") || blockState.startsWith("minecraft:furnace[") ||
                blockState.equals("minecraft:blast_furnace") || blockState.startsWith("minecraft:blast_furnace[") ||
                blockState.equals("minecraft:smoker") || blockState.startsWith("minecraft:smoker[");
    }

    public record CommandMutation(BlockPos pos, BlockState targetState)
    {
        public CommandMutation
        {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(targetState, "targetState");
        }
    }
}
