package me.niicide.lvc.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nullable;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.network.PacketSplitter;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.util.PasteNbtBehavior;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import fi.dy.masa.litematica.util.ToBooleanFunction;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.capture.LvcSiteWorkPlan;
import me.niicide.lvc.capture.LvcWorldReader;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.project.LvcProjectPositions;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.semantic.LvcTrackedBlockCursor;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.world.LvcWorldBackend;

public final class LvcRemoteServerApplyTask extends LvcChunkedTaskBase<LvcRemoteServerApplyTask.Result>
{
    private static final int CLEANUP_COMMANDS_PER_TICK = 4;
    private static final int VOID_ENTITY_CLEANUP_Y = -9999;
    private static final int VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS = 8;
    private static final int VOID_ENTITY_CLEANUP_VERTICAL_RADIUS = 16;
    private static final int SERVUX_PACKET_SLICES_PER_TICK = 32;
    private static final long SERVUX_PACKET_SEND_BUDGET_NANOS = 2_000_000L;
    private static final long SERVUX_CLIENT_SYNC_BUDGET_NANOS = 4_000_000L;
    private static final long COMMAND_FEEDBACK_PROBE_TIMEOUT_NANOS = 2_000_000_000L;
    private static final int CLIENT_SHADOW_SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final Path repositoryDirectory;
    private final Level world;
    private final Mode mode;
    @Nullable private final String targetCommitId;
    @Nullable private final String targetBranchName;
    @Nullable private final String sourceBranchName;
    @Nullable private final String mergePreviousHead;
    @Nullable private final List<BlockPos> furnaceXpCleanupCandidates;

    @Nullable private LvcWorldBackend backend;
    @Nullable private Git git;
    @Nullable private RevWalk revWalk;
    @Nullable private RevCommit targetCommit;
    @Nullable private LvcSemanticSchematicBuilder.BuildSession buildSession;
    @Nullable private LitematicaSchematic schematic;
    @Nullable private LvcManifest manifest;
    @Nullable private String siteId;
    @Nullable private LvcSitePlacement placement;
    @Nullable private LvcSiteWorkPlan cleanupPlan;
    @Nullable private BlockPos origin;
    @Nullable private Result result;
    @Nullable private CommandPasteConfigOverride commandConfigOverride;
    @Nullable private SchematicPlacement commandPlacement;
    @Nullable private LitematicaSchematic commandSchematic;
    @Nullable private LvcWorldReader sparseTargetReader;
    @Nullable private LvcRemoteSparseTargetPlanner sparseTargetPlanner;
    @Nullable private LvcServuxBulkRequestPlanner servuxRequests;
    @Nullable private CompletableFuture<ServuxPastePayload> servuxPastePayloadFuture;
    @Nullable private ServuxPastePayload servuxPastePayload;
    @Nullable private ClientSchematicShadowSync clientShadowSync;
    @Nullable private String previousHead;
    @Nullable private String previousBranch;
    private Phase phase = Phase.BUILD;
    private int regionCount;
    private boolean commandPasteScheduled;
    private boolean commandPasteFinished;
    private boolean commandPasteSucceeded;
    private boolean journalWritten;
    private boolean gitMoved;
    private boolean entityCleanupPrepared;
    private int furnaceXpCleanupChunkIndex;
    private int furnaceXpCleanupCommandsPrepared;
    private int commandReadableValidationBlocks;
    private final Deque<String> cleanupCommandQueue = new ArrayDeque<>();
    private final Deque<String> commandMutationQueue = new ArrayDeque<>();
    private final ToBooleanFunction<Component> commandFeedbackListener = this::checkCommandMutationFeedbackGameRuleState;
    private boolean commandMutationsPrepared;
    private int commandMutationCommandsPrepared;
    private int commandMutationTickDelay;
    private boolean commandFeedbackProbeStarted;
    private boolean commandFeedbackProbeComplete;
    private boolean commandFeedbackShouldRestore;
    private boolean commandFeedbackListenerRegistered;
    private long commandFeedbackProbeTimeout;

    public static LvcRemoteServerApplyTask checkout(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                    String targetCommitId, @Nullable String targetBranchName,
                                                    LvcTaskCallbacks<Result> callbacks)
    {
        return checkout(handle, repositoryDirectory, world, targetCommitId, targetBranchName, null, callbacks);
    }

    public static LvcRemoteServerApplyTask checkout(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                    String targetCommitId, @Nullable String targetBranchName,
                                                    @Nullable List<BlockPos> furnaceXpCleanupCandidates,
                                                    LvcTaskCallbacks<Result> callbacks)
    {
        Mode mode = targetBranchName == null || targetBranchName.isBlank() ? Mode.CHECKOUT : Mode.CHECKOUT_BRANCH;
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, mode, targetCommitId,
                targetBranchName, null, null, furnaceXpCleanupCandidates, callbacks);
    }

    public static LvcRemoteServerApplyTask discard(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                   @Nullable String targetCommitId,
                                                   LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.DISCARD, targetCommitId,
                null, null, null, null, callbacks);
    }

    public static LvcRemoteServerApplyTask clear(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                 LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.CLEAR, null, null, null, null, null, callbacks);
    }

    public static LvcRemoteServerApplyTask deleteVersion(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                         String targetCommitId, String targetBranchName,
                                                         LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.DELETE_VERSION, targetCommitId,
                targetBranchName, null, null, null, callbacks);
    }

    public static LvcRemoteServerApplyTask merge(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                 String targetCommitId, @Nullable String targetBranchName,
                                                 @Nullable String sourceBranchName,
                                                 @Nullable String previousHead,
                                                 LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.MERGE, targetCommitId,
                targetBranchName, sourceBranchName, previousHead, null, callbacks);
    }

    public static void validateRemoteApplyReady(Level world) throws IOException
    {
        Objects.requireNonNull(world, "world");
        LvcWorldBackend backend = LvcWorldBackend.resolve(world);

        if (backend != LvcWorldBackend.DIRECT)
        {
            validateRemoteBackendReady(backend);
        }
    }

    private LvcRemoteServerApplyTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                     Mode mode, @Nullable String targetCommitId,
                                     @Nullable String targetBranchName,
                                     @Nullable String sourceBranchName,
                                     @Nullable String mergePreviousHead,
                                     @Nullable List<BlockPos> furnaceXpCleanupCandidates,
                                     LvcTaskCallbacks<Result> callbacks)
    {
        super(handle, mode.displayName, callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.targetCommitId = normalize(targetCommitId);
        this.targetBranchName = normalize(targetBranchName);
        this.sourceBranchName = normalize(sourceBranchName);
        this.mergePreviousHead = normalize(mergePreviousHead);
        this.furnaceXpCleanupCandidates = furnaceXpCleanupCandidates == null ? null : List.copyOf(furnaceXpCleanupCandidates);
    }

    @Override
    public void init()
    {
        try
        {
            this.backend = LvcWorldBackend.resolve(this.world);

            if (this.backend == LvcWorldBackend.DIRECT)
            {
                throw new IOException("Remote server apply task was scheduled for a direct singleplayer world");
            }

            validateRemoteBackendReady(this.requireBackend());
            if (this.mode == Mode.CLEAR)
            {
                this.manifest = LvcSemanticRepository.readManifest(this.repositoryDirectory);
                this.siteId = LvcSemanticRepository.defaultSiteId(this.manifest);
                this.preparePlacementState();
                this.validateCommandClearReadableIfNeeded();
                this.schematic = LvcSemanticSchematicBuilder.buildAirSchematic(this.manifest, this.siteId, this.requirePlacement());
                this.prepareServuxPastePayloadIfNeeded(this.schematic);
                this.phase = Phase.PREPARE_SERVUX_PAYLOAD;
            }
            else
            {
                this.git = Git.open(this.repositoryDirectory.toFile());
                Repository repository = this.git.getRepository();
                this.revWalk = new RevWalk(repository);
                this.capturePreviousHead(repository);
                this.targetCommit = this.resolveTargetCommit(repository);
                this.manifest = LvcSemanticRepository.readCommitManifest(repository, this.targetCommit);
                this.siteId = LvcSemanticRepository.defaultSiteId(this.manifest);
                this.preparePlacementState();
                LvcSiteWorkPlan sparsePlan = this.shouldBuildSparseTargetSchematic() ?
                        LvcSiteWorkPlan.create(this.requireManifest().site(this.requireSiteId()), this.requirePlacement()) : null;
                this.servuxRequests = sparsePlan != null && this.requireBackend() == LvcWorldBackend.SERVUX ?
                        LvcServuxBulkRequestPlanner.create(sparsePlan) : null;
                this.sparseTargetReader = sparsePlan == null ? null :
                        this.requireBackend().createReader(this.world, this.requireBackend() == LvcWorldBackend.SERVUX);
                this.sparseTargetPlanner = this.sparseTargetReader == null ? null :
                        new LvcRemoteSparseTargetPlanner(this.requireBackend(), this.sparseTargetReader,
                                this.requireManifest().site(this.requireSiteId()));
                this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                        this.manifest,
                        this.siteId,
                        this.requirePlacement(),
                        objectId -> this.readCommitObject(this.requireTargetCommit(), objectId),
                        null,
                        this.sparseTargetPlanner == null ? null : this.sparseTargetPlanner::include
                );
                this.phase = this.servuxRequests == null ? Phase.BUILD : Phase.REQUEST_SERVUX_DATA;
            }

            LvcDiagnostics.debug(this.handle(),
                    "remote server apply initialized mode={} backend={} lossy={} site={} target={} branch='{}' regions={} chunks={} dimension={} origin={} sparseTarget={} servuxColumns={}",
                    this.mode.name(), this.backend.id(), this.backend.lossy(), this.siteId,
                    this.targetCommit == null ? "<none>" : this.targetCommit.getName(),
                    this.targetBranchName == null ? "<none>" : this.targetBranchName,
                    this.regionCount, this.buildSession == null ? 0 : this.buildSession.totalChunks(),
                    this.requirePlacement().dimension(), this.requirePlacement().origin(),
                    this.sparseTargetReader != null, this.servuxRequests == null ? 0 : this.servuxRequests.totalColumns());
            this.updateProgressHud();
        }
        catch (Exception e)
        {
            this.fail(e instanceof Exception exception ? exception : new RuntimeException(e));
        }
    }

    @Override
    protected boolean step() throws Exception
    {
        if (this.phase == Phase.REQUEST_SERVUX_DATA)
        {
            LvcServuxBulkRequestPlanner requests = Objects.requireNonNull(this.servuxRequests, "servuxRequests");

            if (!requests.ensureAllReady(this.handle(), "remote server apply"))
            {
                return false;
            }

            this.phase = Phase.BUILD;
            return false;
        }

        if (this.phase == Phase.BUILD)
        {
            LvcSemanticSchematicBuilder.BuildSession session = this.requireBuildSession();

            if (!session.isComplete())
            {
                session.processNextChunk();
                return false;
            }

            this.schematic = session.result();
            this.prepareServuxPastePayloadIfNeeded(this.schematic);
            LvcRemoteSparseTargetPlanner sparsePlanner = this.sparseTargetPlanner;
            LvcDiagnostics.debug(this.handle(),
                    "remote server apply target schematic built sparse={} chunks={} includedBlocks={} structureVoidBlocks={} scannedBlocks={} stateMismatches={} blockEntityMismatches={} ignoredBlockEntityTargets={} affectedRegions={}",
                    this.sparseTargetReader != null, session.totalChunks(), session.includedBlocks(),
                    session.structureVoidBlocks(), sparsePlanner == null ? 0 : sparsePlanner.scannedBlocks(),
                    sparsePlanner == null ? 0 : sparsePlanner.stateMismatches(),
                    sparsePlanner == null ? 0 : sparsePlanner.blockEntityMismatches(),
                    sparsePlanner == null ? 0 : sparsePlanner.ignoredBlockEntityTargets(),
                    sparsePlanner == null ? 0 : sparsePlanner.affectedRegionIds().size());
            this.phase = Phase.PREPARE_SERVUX_PAYLOAD;
            return false;
        }

        if (this.phase == Phase.PREPARE_SERVUX_PAYLOAD)
        {
            if (!this.completeServuxPastePayloadIfReady())
            {
                return false;
            }

            this.phase = Phase.WRITE_JOURNAL;
            return false;
        }

        if (this.phase == Phase.WRITE_JOURNAL)
        {
            this.writeJournalAndMoveGit();
            LvcDiagnostics.debug(this.handle(), "remote server apply prepared Git/journal mode={} target={}",
                    this.mode.name(), this.targetCommitName());
            this.phase = Phase.CLEAR_ENTITIES;
            return false;
        }

        if (this.phase == Phase.CLEAR_ENTITIES)
        {
            this.prepareEntityCleanupCommands();

            if (this.sendQueuedCleanupCommands())
            {
                LvcDiagnostics.debug(this.handle(),
                        "remote server apply void entity cleanup dispatched regions={} holdY={} horizontalRadius={} verticalRadius={}",
                        this.regionCount, VOID_ENTITY_CLEANUP_Y, VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS,
                        VOID_ENTITY_CLEANUP_VERTICAL_RADIUS);
                this.phase = Phase.CLEAR_FURNACE_XP;
            }

            return false;
        }

        if (this.phase == Phase.CLEAR_FURNACE_XP)
        {
            this.prepareFurnaceXpCleanupCommands();

            if (this.sendQueuedCleanupCommands() && this.furnaceXpCleanupComplete())
            {
                LvcDiagnostics.debug(this.handle(), "remote server apply furnace XP cleanup complete source={} workUnits={} commands={}",
                        this.furnaceXpCleanupSource(),
                        this.furnaceXpCleanupWorkUnits(), this.furnaceXpCleanupCommandsPrepared);
                this.phase = Phase.SEND_PASTE;
            }

            return false;
        }

        if (this.phase == Phase.SEND_PASTE)
        {
            if (!this.processRemotePaste())
            {
                return false;
            }

            this.phase = this.commandPasteScheduled ? Phase.WAIT_COMMANDS :
                    (this.requireBackend() == LvcWorldBackend.SERVUX ? Phase.SYNC_CLIENT : Phase.FINISH);
            return false;
        }

        if (this.phase == Phase.WAIT_COMMANDS)
        {
            if (!this.commandPasteFinished)
            {
                return false;
            }

            if (!this.commandPasteSucceeded)
            {
                throw new IOException("Litematica command paste was aborted");
            }

            this.phase = Phase.FINISH;
            return false;
        }

        if (this.phase == Phase.SYNC_CLIENT)
        {
            if (!this.syncServuxClientShadow())
            {
                return false;
            }

            this.phase = Phase.FINISH;
            return false;
        }

        if (this.phase == Phase.FINISH)
        {
            this.finishRemoteApply();
            this.phase = Phase.DONE;
            return true;
        }

        return true;
    }

    @Override
    protected boolean shouldContinueWithinTick()
    {
        return this.phase == Phase.BUILD ||
                (this.phase == Phase.CLEAR_FURNACE_XP && this.cleanupCommandQueue.isEmpty());
    }

    @Override
    protected Result result()
    {
        return Objects.requireNonNull(this.result, "result");
    }

    @Override
    public void stop()
    {
        try
        {
            super.stop();
        }
        finally
        {
            this.restoreCommandConfig();
            this.restoreCommandFeedback();

            if (this.servuxPastePayload != null)
            {
                this.servuxPastePayload.release();
                this.servuxPastePayload = null;
            }

            if (this.servuxPastePayloadFuture != null)
            {
                CompletableFuture<ServuxPastePayload> future = this.servuxPastePayloadFuture;
                this.servuxPastePayloadFuture = null;

                if (future.isDone() &&
                        !future.isCancelled() &&
                        !future.isCompletedExceptionally())
                {
                    future.join().release();
                }
                else
                {
                    future.whenComplete((payload, throwable) ->
                    {
                        if (payload != null)
                        {
                            payload.release();
                        }
                    });
                }

                future.cancel(true);
            }

            if (this.revWalk != null)
            {
                this.revWalk.close();
            }

            if (this.git != null)
            {
                this.git.close();
            }
        }
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add("Backend: " + (this.backend == null ? "unknown" : this.backend.id()));
        this.infoHudLines.add("Phase: " + this.phase.label);

        if (this.phase == Phase.REQUEST_SERVUX_DATA && this.servuxRequests != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Servux columns: %d / %d",
                    this.servuxRequests.completedColumns(), this.servuxRequests.totalColumns()));
        }
        else if (this.phase == Phase.BUILD && this.buildSession != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Chunks: %d / %d",
                    this.buildSession.processedChunks(), this.buildSession.totalChunks()));
        }
        else if (this.phase == Phase.PREPARE_SERVUX_PAYLOAD && this.servuxPastePayloadFuture != null)
        {
            this.infoHudLines.add(this.servuxPastePayloadFuture.isDone() ? "Servux payload: ready" :
                    "Servux payload: preparing");
        }
        else if ((this.phase == Phase.CLEAR_ENTITIES || this.phase == Phase.CLEAR_FURNACE_XP) &&
                this.cleanupCommandQueue.isEmpty() == false)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Cleanup commands: %d",
                    this.cleanupCommandQueue.size()));
        }
        else if (this.phase == Phase.CLEAR_FURNACE_XP && this.cleanupPlan != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Furnace XP cleanup: %d / %d",
                    Math.min(this.furnaceXpCleanupChunkIndex, this.cleanupPlan.chunkCount()),
                    this.cleanupPlan.chunkCount()));
        }
        else if (this.phase == Phase.CLEAR_FURNACE_XP && this.activeFurnaceXpCleanupCandidates() != null)
        {
            List<BlockPos> candidates = Objects.requireNonNull(this.activeFurnaceXpCleanupCandidates(), "activeFurnaceXpCleanupCandidates");
            this.infoHudLines.add(String.format(Locale.ROOT, "Furnace XP cleanup: %d / %d",
                    Math.min(this.furnaceXpCleanupChunkIndex, candidates.size()),
                    candidates.size()));
        }
        else if (this.phase == Phase.SEND_PASTE && this.servuxPastePayload != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Servux send: %d / %d slices",
                    this.servuxPastePayload.sentSlices(), this.servuxPastePayload.totalSlices()));
        }
        else if (this.phase == Phase.SEND_PASTE && this.commandMutationsPrepared)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Commands: %d",
                    this.commandMutationQueue.size()));
        }
        else if (this.phase == Phase.SYNC_CLIENT && this.clientShadowSync != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Client sync: %d / %d blocks",
                    this.clientShadowSync.processedVolume(), this.clientShadowSync.totalVolume()));
        }
    }

    private void preparePlacementState() throws IOException
    {
        LvcManifest currentManifest = Objects.requireNonNull(this.manifest, "manifest");
        String currentSiteId = Objects.requireNonNull(this.siteId, "siteId");
        LvcManifest.Site site = currentManifest.site(currentSiteId);
        LvcSitePlacement sitePlacement = LvcTrackingOverlayService.requireCurrentOrCachedSitePlacement(this.repositoryDirectory, site);

        LvcSemanticTaskContext.validatePlacementDimension(sitePlacement, this.world);
        this.placement = sitePlacement;
        this.cleanupPlan = this.furnaceXpCleanupCandidates == null && !this.shouldBuildSparseTargetSchematic() ?
                LvcSiteWorkPlan.create(site, sitePlacement) : null;
        this.origin = LvcProjectPositions.blockPosFromList(sitePlacement.origin());
        this.regionCount = site.regions().size();

        if (this.regionCount == 0)
        {
            throw new IOException("LVC project has no tracked sub-regions");
        }
    }

    private void validateCommandClearReadableIfNeeded() throws IOException
    {
        if (this.requireBackend() != LvcWorldBackend.COMMANDS || this.mode != Mode.CLEAR)
        {
            return;
        }

        LvcWorldReader reader = this.requireBackend().createReader(this.world);
        LvcSiteWorkPlan plan = LvcSiteWorkPlan.create(
                this.requireManifest().site(this.requireSiteId()), this.requirePlacement());

        for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
        {
            for (LvcTrackedBlockCursor.Position tracked : LvcTrackedBlockCursor.positions(work.coordinate(), plan.origin(), work.mask(),
                    LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
            {
                this.commandReadableValidationBlocks++;

                if (!reader.canReadAt(tracked.worldPos()))
                {
                    throw new IOException("LVC remote command clear cannot read tracked block at " + tracked.blockPos());
                }
            }
        }

        LvcDiagnostics.debug(this.handle(), "remote command clear readability validated blocks={} regions={}",
                this.commandReadableValidationBlocks, this.regionCount);
    }

    private boolean shouldBuildSparseTargetSchematic()
    {
        return this.mode != Mode.CLEAR;
    }

    private int sparseSkippedBlocks()
    {
        return this.buildSession == null ? 0 : this.buildSession.structureVoidBlocks();
    }

    private boolean processRemotePaste() throws Exception
    {
        LitematicaSchematic currentSchematic = Objects.requireNonNull(this.schematic, "schematic");
        LvcWorldBackend currentBackend = this.requireBackend();

        if (currentBackend == LvcWorldBackend.SERVUX)
        {
            boolean complete = this.sendServuxPaste();

            if (complete)
            {
                LvcDiagnostics.info(this.handle(), "remote server apply sent Servux paste mode={} regions={} target={}",
                        this.mode.name(), this.regionCount, this.targetCommitName());
            }

            return complete;
        }

        if (!this.commandPasteScheduled)
        {
            LvcRemoteSparseTargetPlanner sparsePlanner = this.sparseTargetPlanner;

            if (sparsePlanner != null)
            {
                if (!this.commandMutationsPrepared)
                {
                    this.stripUnsupportedCommandPayloads(currentSchematic);
                }

                return this.processSparseCommandMutations(sparsePlanner);
            }

            this.stripUnsupportedCommandPayloads(currentSchematic);
            this.scheduleCommandPaste(currentSchematic);
            LvcDiagnostics.info(this.handle(), "remote server apply scheduled command paste mode={} regions={} target={} sparse={} changedBlocks={} skippedBlocks={} ignoredBlockEntityTargets={}",
                    this.mode.name(), this.regionCount, this.targetCommitName(), this.shouldBuildSparseTargetSchematic(),
                    sparsePlanner == null ? 0 : sparsePlanner.stateMismatches(),
                    this.schematic == null ? 0 : this.sparseSkippedBlocks(),
                    sparsePlanner == null ? 0 : sparsePlanner.ignoredBlockEntityTargets());
        }

        return true;
    }

    private static void validateRemoteBackendReady(LvcWorldBackend backend) throws IOException
    {
        if (hasGamemasterCommandPermission())
        {
            return;
        }

        String hint = "";

        if (Configs.Generic.PASTE_USING_SERVUX.getBooleanValue())
        {
            if (!Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
            {
                hint = " Litematica entityDataSync is disabled, so Servux paste cannot be used yet.";
            }
            else if (!EntityDataManager.getInstance().hasServuxServer())
            {
                hint = " Servux paste is enabled, but the client has not completed a Servux handshake yet.";
            }
        }

        if (backend == LvcWorldBackend.SERVUX)
        {
            throw new IOException("Remote Servux apply requires gamemaster command permission to clear entities before paste.");
        }

        throw new IOException("Remote command fallback requires gamemaster command permission on the server." + hint);
    }

    private static boolean hasGamemasterCommandPermission()
    {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private void writeJournalAndMoveGit() throws Exception
    {
        if (this.gitMoved)
        {
            return;
        }

        this.writeJournalIfNeeded(this.mode.journalPhase);

        if (this.mode == Mode.CHECKOUT || this.mode == Mode.CHECKOUT_BRANCH)
        {
            if (this.targetBranchName != null)
            {
                LvcProjectGitOps.checkoutBranchToWorkingTree(this.repositoryDirectory, this.targetBranchName);
            }
            else
            {
                LvcProjectGitOps.checkoutCommitToWorkingTree(this.repositoryDirectory, this.requireTargetCommit().getName());
            }
        }
        else if (this.mode == Mode.DISCARD)
        {
            LvcProjectGitOps.resetWorkingTreeToHead(this.repositoryDirectory);
        }
        else if (this.mode == Mode.DELETE_VERSION)
        {
            if (this.targetBranchName == null || this.targetBranchName.isBlank())
            {
                throw new IOException("Remote delete-version apply requires a target branch");
            }

            LvcProjectGitOps.checkoutBranchAndResetToCommit(this.repositoryDirectory, this.targetBranchName,
                    this.requireTargetCommit().getName());
        }
        else if (this.mode == Mode.MERGE)
        {
            // Merge Git mutation already happened before remote paste so conflict UI can run synchronously.
        }

        this.gitMoved = true;
    }

    private void writeJournalIfNeeded(String phase) throws IOException
    {
        if (this.journalWritten)
        {
            return;
        }

        if (this.mode == Mode.CHECKOUT || this.mode == Mode.CHECKOUT_BRANCH)
        {
            LvcOperationJournal.writeCheckout(this.repositoryDirectory, this.requireTargetCommit().getName(),
                    this.targetBranchName, this.previousHead, this.previousBranch, phase);
        }
        else if (this.mode == Mode.DELETE_VERSION)
        {
            LvcOperationJournal.writeDeleteVersion(this.repositoryDirectory, this.requireTargetCommit().getName(),
                    this.targetBranchName, this.previousHead, this.previousBranch, phase);
        }
        else if (this.mode == Mode.MERGE)
        {
            LvcOperationJournal.write(this.repositoryDirectory, LvcOperationJournal.Operation.MERGE,
                    this.requireTargetCommit().getName(), this.targetBranchName, this.sourceBranchName,
                    this.mergePreviousHead, phase);
        }
        else
        {
            LvcOperationJournal.write(this.repositoryDirectory, this.mode.journalOperation,
                    this.targetCommitName(), this.mode.journalPhase);
        }

        this.journalWritten = true;
    }

    private void finishRemoteApply() throws IOException
    {
        LvcRefreshMarker.write(this.repositoryDirectory, this.mode.journalPhase, this.targetCommitName());
        LvcOperationJournal.delete(this.repositoryDirectory);
        this.result = new Result(this.mode, this.requireBackend(), this.regionCount,
                this.requireBackend().lossy(), this.commandPasteScheduled);
        LvcDiagnostics.debug(this.handle(), "remote server apply complete mode={} backend={} lossy={} commandScheduled={} regions={} target={}",
                this.mode.name(), this.requireBackend().id(), this.requireBackend().lossy(),
                this.commandPasteScheduled, this.regionCount, this.targetCommitName());
    }

    private boolean sendServuxPaste() throws IOException
    {
        ServuxPastePayload payload = Objects.requireNonNull(this.servuxPastePayload, "servuxPastePayload");
        ClientPacketListener connection = Minecraft.getInstance().getConnection();

        if (connection == null)
        {
            throw new IOException("Cannot send remote Servux paste: client connection is unavailable");
        }

        int sent = payload.sendNextBatch(connection);

        if (sent > 0)
        {
            LvcDiagnostics.debug(this.handle(),
                    "remote Servux paste sent packet slices sent={} sentSlices={}/{} sentBytes={}/{}",
                    sent, payload.sentSlices(), payload.totalSlices(), payload.sentBytes(), payload.encodedBytes());
        }

        if (!payload.isComplete())
        {
            return false;
        }

        payload.release();
        this.servuxPastePayload = null;
        return true;
    }

    private void prepareServuxPastePayloadIfNeeded(LitematicaSchematic currentSchematic) throws IOException
    {
        if (this.requireBackend() != LvcWorldBackend.SERVUX ||
                this.servuxPastePayload != null || this.servuxPastePayloadFuture != null)
        {
            return;
        }

        SchematicPlacement pastePlacement = this.createServuxPastePlacement(currentSchematic);
        this.servuxPastePayloadFuture = CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return this.createServuxPastePayload(pastePlacement);
            }
            catch (IOException e)
            {
                throw new CompletionException(e);
            }
        });
        LvcDiagnostics.debug(this.handle(), "remote Servux paste payload preparation started mode={} regions={} target={}",
                this.mode.name(), this.regionCount, this.targetCommitName());
    }

    private boolean completeServuxPastePayloadIfReady() throws IOException
    {
        if (this.requireBackend() != LvcWorldBackend.SERVUX)
        {
            return true;
        }

        if (this.servuxPastePayload != null)
        {
            return true;
        }

        CompletableFuture<ServuxPastePayload> future = Objects.requireNonNull(this.servuxPastePayloadFuture,
                "servuxPastePayloadFuture");

        if (!future.isDone())
        {
            return false;
        }

        try
        {
            this.servuxPastePayload = future.join();
            this.servuxPastePayloadFuture = null;
        }
        catch (CompletionException e)
        {
            throw unwrapServuxPayloadException(e);
        }

        ServuxPastePayload payload = Objects.requireNonNull(this.servuxPastePayload, "servuxPastePayload");
        LvcDiagnostics.debug(this.handle(),
                "remote Servux paste payload prepared nbtBytes={} encodedBytes={} maxInlineBytes={} slices={}",
                payload.nbtBytes(), payload.encodedBytes(), payload.maxInlineBytes(), payload.totalSlices());
        return true;
    }

    private ServuxPastePayload createServuxPastePayload(SchematicPlacement pastePlacement) throws IOException
    {
        CompoundTag nbt = pastePlacement.toNbt(true);
        nbt.putString("Task", "LitematicaPaste");
        nbt.putString("ReplaceMode", ReplaceBehavior.ALL.getStringValue());
        nbt.putString("PasteLayerBehavior", PasteLayerBehavior.ALL.getStringValue());
        int maxSize = servuxPasteMaxInlineBytes();
        int nbtBytes = nbt.sizeInBytes();

        if (nbtBytes > maxSize)
        {
            LvcDiagnostics.warn("{} remote Servux paste refused oversized payload nbtBytes={} maxInlineBytes={} mode={} regions={} target={}",
                    LvcDiagnostics.operationTag(this.handle()), nbtBytes, maxSize,
                    this.mode.name(), this.regionCount, this.targetCommitName());
            throw new IOException(String.format(Locale.ROOT,
                    "Remote Servux paste is too large to transmit safely (%d bytes, max %d). Split the tracked area or reduce changed positions before retrying.",
                    nbtBytes, maxSize));
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        try
        {
            buffer.writeVarInt(-1);
            buffer.writeNbt(nbt);
            return new ServuxPastePayload(buffer, nbtBytes, maxSize);
        }
        catch (RuntimeException e)
        {
            buffer.release();
            throw e;
        }
    }

    private SchematicPlacement createServuxPastePlacement(LitematicaSchematic currentSchematic)
    {
        return SchematicPlacement.createFor(currentSchematic, this.requireOrigin(),
                this.remotePlacementName(), true, false);
    }

    private static int servuxPasteMaxInlineBytes()
    {
        return PacketSplitter.DEFAULT_MAX_RECEIVE_SIZE_S2C - 4096;
    }

    private static IOException unwrapServuxPayloadException(CompletionException e)
    {
        Throwable cause = e.getCause();

        if (cause instanceof IOException ioException)
        {
            return ioException;
        }

        return new IOException("Failed to prepare remote Servux paste payload", cause == null ? e : cause);
    }

    private boolean syncServuxClientShadow() throws IOException
    {
        if (this.requireBackend() != LvcWorldBackend.SERVUX)
        {
            return true;
        }

        ClientLevel clientLevel = Minecraft.getInstance().level;

        if (clientLevel == null || !this.world.dimension().equals(clientLevel.dimension()))
        {
            LvcDiagnostics.debug(this.handle(),
                    "remote Servux client shadow sync skipped reason='client level unavailable or different dimension' mode={} regions={} target={}",
                    this.mode.name(), this.regionCount, this.targetCommitName());
            return true;
        }

        if (this.clientShadowSync == null)
        {
            this.clientShadowSync = new ClientSchematicShadowSync(clientLevel,
                    Objects.requireNonNull(this.schematic, "schematic"), this.requireOrigin());
            LvcDiagnostics.debug(this.handle(),
                    "remote Servux client shadow sync started mode={} regions={} target={} regionsInSchematic={} volume={}",
                    this.mode.name(), this.regionCount, this.targetCommitName(),
                    this.clientShadowSync.regionCount(), this.clientShadowSync.totalVolume());
        }

        ClientSchematicShadowSync sync = Objects.requireNonNull(this.clientShadowSync, "clientShadowSync");

        if (!sync.processNextBatch())
        {
            return false;
        }

        sync.refreshRenderState();
        LvcDiagnostics.debug(this.handle(),
                "remote Servux client shadow sync complete mode={} regions={} target={} processedVolume={} pastedBlocks={} changedBlocks={} skippedStructureVoid={} skippedUnloaded={} renderSections={} renderChunks={}",
                this.mode.name(), this.regionCount, this.targetCommitName(), sync.processedVolume(),
                sync.pastedBlocks(), sync.changedBlocks(), sync.skippedStructureVoid(), sync.skippedUnloaded(),
                sync.renderSectionCount(), sync.renderChunkCount());
        this.clientShadowSync = null;
        return true;
    }

    private void scheduleCommandPaste(LitematicaSchematic currentSchematic)
            throws IOException
    {
        this.commandConfigOverride = CommandPasteConfigOverride.apply();
        LitematicaSchematic fileBackedSchematic = this.writeAndReloadTempSchematic(currentSchematic);
        SchematicHolder.getInstance().addSchematic(fileBackedSchematic, false);
        SchematicPlacement pastePlacement = SchematicPlacement.createFor(fileBackedSchematic, this.requireOrigin(),
                this.remotePlacementName(), true, false);
        pastePlacement.setShouldBeSaved(false);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(pastePlacement, false);
        this.commandSchematic = fileBackedSchematic;
        this.commandPlacement = pastePlacement;
        LayerRange range = DataManager.getRenderLayerRange();
        LvcSparseCommandPasteTask task = new LvcSparseCommandPasteTask(List.of(pastePlacement), range, false);
        task.disableCompletionMessage();
        task.setCompletionListener(new ICompletionListener()
        {
            @Override
            public void onTaskCompleted()
            {
                commandPasteSucceeded = true;
                commandPasteFinished = true;
                cleanupCommandPlacement();
                restoreCommandConfig();
            }

            @Override
            public void onTaskAborted()
            {
                commandPasteSucceeded = false;
                commandPasteFinished = true;
                cleanupCommandPlacement();
                restoreCommandConfig();
            }
        });
        this.commandPasteScheduled = true;
        TaskScheduler.getInstanceClient().scheduleTask(task, Configs.Generic.COMMAND_TASK_INTERVAL.getIntegerValue());
    }

    private boolean processSparseCommandMutations(LvcRemoteSparseTargetPlanner sparsePlanner) throws IOException
    {
        if (!this.commandMutationsPrepared)
        {
            this.prepareSparseCommandMutationCommands(sparsePlanner);
        }

        if (this.commandMutationQueue.isEmpty())
        {
            return true;
        }

        if (!this.ensureCommandFeedbackReady())
        {
            return false;
        }

        boolean complete = this.sendQueuedCommandMutationCommands();

        if (complete)
        {
            this.restoreCommandFeedback();
        }

        return complete;
    }

    private void prepareSparseCommandMutationCommands(LvcRemoteSparseTargetPlanner sparsePlanner)
    {
        if (this.commandMutationsPrepared)
        {
            return;
        }

        for (LvcRemoteSparseTargetPlanner.CommandMutation mutation : sparsePlanner.commandMutations())
        {
            this.enqueueCommandMutation(mutation.pos(), mutation.targetState());
        }

        this.commandMutationsPrepared = true;
        LvcDiagnostics.info(this.handle(),
                "remote command sparse apply prepared mode={} regions={} target={} blockMutations={} commands={} skippedBlocks={} ignoredBlockEntityTargets={}",
                this.mode.name(), this.regionCount, this.targetCommitName(), sparsePlanner.commandMutations().size(),
                this.commandMutationCommandsPrepared, this.sparseSkippedBlocks(),
                sparsePlanner.ignoredBlockEntityTargets());
    }

    private void enqueueCommandMutation(BlockPos pos, BlockState state)
    {
        String blockString = BlockStateParser.serialize(state);

        if (Configs.Generic.COMMAND_USE_WORLDEDIT.getBooleanValue())
        {
            this.commandMutationQueue.addLast(String.format(Locale.ROOT, "/pos1 %d,%d,%d",
                    pos.getX(), pos.getY(), pos.getZ()));
            this.commandMutationQueue.addLast(String.format(Locale.ROOT, "/pos2 %d,%d,%d",
                    pos.getX(), pos.getY(), pos.getZ()));
            this.commandMutationQueue.addLast("/set " + blockString);
            this.commandMutationCommandsPrepared += 3;
            return;
        }

        String strict = Configs.Generic.COMMAND_USE_STRICT.getBooleanValue() ? " strict" : "";
        this.commandMutationQueue.addLast(String.format(Locale.ROOT, "%s %d %d %d %s%s",
                Configs.Generic.COMMAND_NAME_SETBLOCK.getStringValue(),
                pos.getX(), pos.getY(), pos.getZ(), blockString, strict));
        this.commandMutationCommandsPrepared++;
    }

    private boolean ensureCommandFeedbackReady()
    {
        if (this.commandFeedbackProbeComplete)
        {
            this.removeCommandFeedbackListener();
            return true;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (!Configs.Generic.COMMAND_DISABLE_FEEDBACK.getBooleanValue() || player == null)
        {
            this.commandFeedbackProbeComplete = true;
            return true;
        }

        if (!this.commandFeedbackProbeStarted)
        {
            DataManager.addChatListener(this.commandFeedbackListener);
            this.commandFeedbackListenerRegistered = true;
            player.connection.sendCommand("gamerule send_command_feedback");
            this.commandFeedbackProbeTimeout = Util.getNanos() + COMMAND_FEEDBACK_PROBE_TIMEOUT_NANOS;
            this.commandFeedbackProbeStarted = true;
            return false;
        }

        if (Util.getNanos() > this.commandFeedbackProbeTimeout)
        {
            this.commandFeedbackProbeComplete = true;
            this.removeCommandFeedbackListener();
            LvcDiagnostics.debug(this.handle(), "remote command sparse apply feedback probe timed out");
            return true;
        }

        return false;
    }

    private boolean checkCommandMutationFeedbackGameRuleState(Component message)
    {
        if (message instanceof MutableComponent mutableText &&
                mutableText.getContents() instanceof TranslatableContents text &&
                "commands.gamerule.query".equals(text.getKey()))
        {
            Object[] args = text.getArgs();
            this.commandFeedbackShouldRestore = args.length == 1 && args[0].equals("true");
            this.commandFeedbackProbeComplete = true;

            if (this.commandFeedbackShouldRestore)
            {
                LocalPlayer player = Minecraft.getInstance().player;

                if (player != null)
                {
                    player.connection.sendCommand("gamerule send_command_feedback false");
                }
            }

            return true;
        }

        return false;
    }

    private boolean sendQueuedCommandMutationCommands() throws IOException
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            throw new IOException("Cannot send remote command apply: client player is unavailable");
        }

        if (this.commandMutationQueue.isEmpty())
        {
            return true;
        }

        if (this.commandMutationTickDelay > 0)
        {
            this.commandMutationTickDelay--;
            return false;
        }

        int sent = 0;
        int maxCommands = Math.max(1, Configs.Generic.COMMAND_LIMIT.getIntegerValue());

        while (sent < maxCommands && this.commandMutationQueue.isEmpty() == false)
        {
            player.connection.sendCommand(this.commandMutationQueue.removeFirst());
            sent++;
        }

        this.commandMutationTickDelay = Math.max(0, Configs.Generic.COMMAND_TASK_INTERVAL.getIntegerValue() - 1);

        if (sent > 0)
        {
            LvcDiagnostics.debug(this.handle(),
                    "remote command sparse apply sent command batch sent={} remaining={}",
                    sent, this.commandMutationQueue.size());
        }

        return this.commandMutationQueue.isEmpty();
    }

    private void restoreCommandFeedback()
    {
        this.removeCommandFeedbackListener();

        if (this.commandFeedbackShouldRestore)
        {
            LocalPlayer player = Minecraft.getInstance().player;

            if (player != null)
            {
                player.connection.sendCommand("gamerule send_command_feedback true");
            }
        }

        this.commandFeedbackShouldRestore = false;
    }

    private void removeCommandFeedbackListener()
    {
        if (this.commandFeedbackListenerRegistered)
        {
            DataManager.removeChatListener(this.commandFeedbackListener);
            this.commandFeedbackListenerRegistered = false;
        }
    }

    private void stripUnsupportedCommandPayloads(LitematicaSchematic currentSchematic)
    {
        int blockEntities = 0;
        int entities = 0;

        for (String regionName : currentSchematic.getAreas().keySet())
        {
            Map<BlockPos, CompoundTag> blockEntityMap = currentSchematic.getBlockEntityMapForRegion(regionName);

            if (blockEntityMap != null)
            {
                blockEntities += blockEntityMap.size();
                blockEntityMap.clear();
            }

            List<EntityInfo> entityList = currentSchematic.getEntityListForRegion(regionName);

            if (entityList != null)
            {
                entities += entityList.size();
                entityList.clear();
            }
        }

        LvcDiagnostics.debug(this.handle(), "remote command apply stripped unsupported payloads blockEntities={} entities={}",
                blockEntities, entities);
    }

    private void prepareEntityCleanupCommands()
    {
        if (this.entityCleanupPrepared)
        {
            return;
        }

        int commands = this.enqueueVoidEntityCleanupCommands();
        this.entityCleanupPrepared = true;

        LvcDiagnostics.debug(this.handle(),
                "remote server apply prepared void entity cleanup commands regions={} commands={} holdY={} horizontalRadius={} verticalRadius={} batchSize={}",
                this.regionCount, commands, VOID_ENTITY_CLEANUP_Y, VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS,
                VOID_ENTITY_CLEANUP_VERTICAL_RADIUS, CLEANUP_COMMANDS_PER_TICK);
    }

    private void prepareFurnaceXpCleanupCommands()
    {
        if (this.cleanupCommandQueue.isEmpty() == false)
        {
            return;
        }

        if (this.activeFurnaceXpCleanupCandidates() != null)
        {
            this.prepareCandidateFurnaceXpCleanupCommands();
            return;
        }

        LvcSiteWorkPlan plan = this.requireCleanupPlan();

        if (this.furnaceXpCleanupChunkIndex < plan.chunks().size())
        {
            LvcSiteWorkPlan.ChunkWork work = plan.chunks().get(this.furnaceXpCleanupChunkIndex);
            this.furnaceXpCleanupChunkIndex++;

            for (LvcTrackedBlockCursor.Position tracked : LvcTrackedBlockCursor.positions(work.coordinate(), plan.origin(), work.mask(),
                    LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
            {
                if (!this.world.hasChunk(SectionPos.blockToSectionCoord(tracked.blockPos().getX()),
                        SectionPos.blockToSectionCoord(tracked.blockPos().getZ())))
                {
                    continue;
                }

                BlockState state = this.world.getBlockState(tracked.blockPos());

                if (isFurnaceLike(state))
                {
                    this.enqueueFurnaceRecipesUsedCleanupCommand(tracked.blockPos());
                }
            }
        }
    }

    private void prepareCandidateFurnaceXpCleanupCommands()
    {
        List<BlockPos> candidates = Objects.requireNonNull(this.activeFurnaceXpCleanupCandidates(), "activeFurnaceXpCleanupCandidates");

        while (this.furnaceXpCleanupChunkIndex < candidates.size() && this.cleanupCommandQueue.isEmpty())
        {
            BlockPos pos = candidates.get(this.furnaceXpCleanupChunkIndex);
            this.furnaceXpCleanupChunkIndex++;

            if (!this.world.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))
            {
                continue;
            }

            BlockState state = this.world.getBlockState(pos);

            if (isFurnaceLike(state))
            {
                this.enqueueFurnaceRecipesUsedCleanupCommand(pos);
            }
        }
    }

    private boolean furnaceXpCleanupComplete()
    {
        return this.furnaceXpCleanupChunkIndex >= this.furnaceXpCleanupWorkUnits();
    }

    private int furnaceXpCleanupWorkUnits()
    {
        List<BlockPos> candidates = this.activeFurnaceXpCleanupCandidates();
        return candidates == null ? this.requireCleanupPlan().chunks().size() : candidates.size();
    }

    private String furnaceXpCleanupSource()
    {
        if (this.shouldBuildSparseTargetSchematic())
        {
            return "sparse";
        }

        return this.furnaceXpCleanupCandidates == null ? "scan" : "preflight";
    }

    @Nullable
    private List<BlockPos> activeFurnaceXpCleanupCandidates()
    {
        if (this.shouldBuildSparseTargetSchematic())
        {
            LvcRemoteSparseTargetPlanner sparsePlanner = Objects.requireNonNull(this.sparseTargetPlanner,
                    "sparseTargetPlanner");
            return sparsePlanner.furnaceXpCleanupCandidates();
        }

        return this.furnaceXpCleanupCandidates;
    }

    private void enqueueFurnaceRecipesUsedCleanupCommand(BlockPos pos)
    {
        String command = String.format(Locale.ROOT, "data remove block %d %d %d RecipesUsed",
                pos.getX(), pos.getY(), pos.getZ());
        this.cleanupCommandQueue.addLast(command);
        this.furnaceXpCleanupCommandsPrepared++;
    }

    private static boolean isFurnaceLike(BlockState state)
    {
        return state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER);
    }

    private int enqueueVoidEntityCleanupCommands()
    {
        BlockPos siteOrigin = this.requireOrigin();
        int commands = 0;

        for (LvcManifest.Region region : this.requireManifest().site(this.requireSiteId()).regions())
        {
            BlockPos min = siteOrigin.offset(LvcProjectPositions.blockPosFromList(region.min()));
            BlockPos size = LvcProjectPositions.blockPosFromList(region.size());
            int holdX = min.getX() + Math.max(0, size.getX() - 1) / 2;
            int holdZ = min.getZ() + Math.max(0, size.getZ() - 1) / 2;
            int holdMinX = holdX - VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS;
            int holdMinY = VOID_ENTITY_CLEANUP_Y - VOID_ENTITY_CLEANUP_VERTICAL_RADIUS;
            int holdMinZ = holdZ - VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS;
            int holdDx = VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS * 2;
            int holdDy = VOID_ENTITY_CLEANUP_VERTICAL_RADIUS * 2;
            int holdDz = VOID_ENTITY_CLEANUP_HORIZONTAL_RADIUS * 2;
            String teleportCommand = String.format(Locale.ROOT,
                    "tp @e[type=!player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d] %d %d %d",
                    min.getX(), min.getY(), min.getZ(),
                    Math.max(0, size.getX() - 1), Math.max(0, size.getY() - 1), Math.max(0, size.getZ() - 1),
                    holdX, VOID_ENTITY_CLEANUP_Y, holdZ);
            String killCommand = String.format(Locale.ROOT,
                    "kill @e[type=!player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                    holdMinX, holdMinY, holdMinZ, holdDx, holdDy, holdDz);
            this.cleanupCommandQueue.addLast(teleportCommand);
            this.cleanupCommandQueue.addLast(killCommand);
            commands += 2;
        }

        return commands;
    }

    private boolean sendQueuedCleanupCommands()
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            this.cleanupCommandQueue.clear();
            return true;
        }

        int sent = 0;

        while (sent < CLEANUP_COMMANDS_PER_TICK && this.cleanupCommandQueue.isEmpty() == false)
        {
            player.connection.sendCommand(this.cleanupCommandQueue.removeFirst());
            sent++;
        }

        if (sent > 0)
        {
            LvcDiagnostics.debug(this.handle(), "remote server apply sent cleanup command batch sent={} remaining={}",
                    sent, this.cleanupCommandQueue.size());
        }

        return this.cleanupCommandQueue.isEmpty();
    }

    private LitematicaSchematic writeAndReloadTempSchematic(LitematicaSchematic currentSchematic) throws IOException
    {
        Path cacheDirectory = LvcOperationJournal.gitLocalDirectory(this.repositoryDirectory).resolve("lvc-cache");
        String fileName = "remote-apply-" + this.handle().id() + LitematicaSchematic.FILE_EXTENSION;
        Files.createDirectories(cacheDirectory);

        if (!currentSchematic.writeToFile(cacheDirectory, fileName, true))
        {
            throw new IOException("Failed to write remote apply litematic: " + cacheDirectory.resolve(fileName));
        }

        LitematicaSchematic fileBacked = LitematicaSchematic.createFromFile(cacheDirectory, fileName);

        if (fileBacked == null)
        {
            throw new IOException("Failed to reload remote apply litematic: " + cacheDirectory.resolve(fileName));
        }

        return fileBacked;
    }

    private RevCommit resolveTargetCommit(Repository repository) throws Exception
    {
        RevWalk currentRevWalk = Objects.requireNonNull(this.revWalk, "revWalk");

        if (this.targetCommitId != null)
        {
            return LvcProjectGitOps.resolveCommit(repository, currentRevWalk, this.targetCommitId);
        }

        ObjectId head = repository.resolve(Constants.HEAD + "^{commit}");

        if (head == null)
        {
            head = repository.resolve(Constants.HEAD);
        }

        if (head == null)
        {
            throw new IOException("LVC repository has no HEAD commit");
        }

        return currentRevWalk.parseCommit(head);
    }

    private void capturePreviousHead(Repository repository) throws IOException
    {
        ObjectId head = repository.resolve(Constants.HEAD + "^{commit}");

        if (head == null)
        {
            head = repository.resolve(Constants.HEAD);
        }

        this.previousHead = head == null ? null : head.name();
        String fullBranch = repository.getFullBranch();
        this.previousBranch = fullBranch != null && fullBranch.startsWith(Constants.R_HEADS) ?
                fullBranch.substring(Constants.R_HEADS.length()) : null;
    }

    private byte[] readCommitObject(RevCommit commit, String objectId) throws IOException
    {
        byte[] bytes = LvcProjectGitOps.readCommitFile(this.requireRepository(), commit,
                LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectId);
        }

        return bytes;
    }

    private Repository requireRepository()
    {
        if (this.git == null)
        {
            throw new IllegalStateException("LVC remote apply task has no open Git repository");
        }

        return this.git.getRepository();
    }

    private LvcSemanticSchematicBuilder.BuildSession requireBuildSession()
    {
        return Objects.requireNonNull(this.buildSession, "buildSession");
    }

    private RevCommit requireTargetCommit()
    {
        return Objects.requireNonNull(this.targetCommit, "targetCommit");
    }

    private LvcWorldBackend requireBackend()
    {
        return Objects.requireNonNull(this.backend, "backend");
    }

    private LvcManifest requireManifest()
    {
        return Objects.requireNonNull(this.manifest, "manifest");
    }

    private String requireSiteId()
    {
        return Objects.requireNonNull(this.siteId, "siteId");
    }

    private LvcSitePlacement requirePlacement()
    {
        return Objects.requireNonNull(this.placement, "placement");
    }

    private LvcSiteWorkPlan requireCleanupPlan()
    {
        return Objects.requireNonNull(this.cleanupPlan, "cleanupPlan");
    }

    private BlockPos requireOrigin()
    {
        return Objects.requireNonNull(this.origin, "origin");
    }

    @Nullable
    private String targetCommitName()
    {
        return this.targetCommit == null ? null : this.targetCommit.getName();
    }

    private String remotePlacementName()
    {
        String projectName = this.requireManifest().name();
        String suffix = this.targetCommitName();

        if (suffix == null)
        {
            suffix = this.mode.name().toLowerCase(Locale.ROOT);
        }
        else
        {
            suffix = suffix.substring(0, Math.min(8, suffix.length()));
        }

        return projectName + " GitMatica " + suffix;
    }

    private void restoreCommandConfig()
    {
        if (this.commandConfigOverride != null)
        {
            this.commandConfigOverride.restore();
            this.commandConfigOverride = null;
        }
    }

    private void cleanupCommandPlacement()
    {
        if (this.commandPlacement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.commandPlacement, true);
            this.commandPlacement = null;
        }

        if (this.commandSchematic != null)
        {
            SchematicHolder.getInstance().removeSchematic(this.commandSchematic);
            this.commandSchematic = null;
        }
    }

    @Nullable
    private static String normalize(@Nullable String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class ServuxPastePayload
    {
        private final FriendlyByteBuf buffer;
        private final int nbtBytes;
        private final int maxInlineBytes;
        private final int encodedBytes;
        private final int totalSlices;
        private int sentBytes;
        private int sentSlices;
        private boolean released;

        private ServuxPastePayload(FriendlyByteBuf buffer, int nbtBytes, int maxInlineBytes)
        {
            this.buffer = Objects.requireNonNull(buffer, "buffer");
            this.nbtBytes = nbtBytes;
            this.maxInlineBytes = maxInlineBytes;
            this.encodedBytes = buffer.writerIndex();
            this.totalSlices = Math.max(1, (this.encodedBytes + PacketSplitter.MAX_PAYLOAD_PER_PACKET_C2S - 1) /
                    PacketSplitter.MAX_PAYLOAD_PER_PACKET_C2S);
            this.buffer.resetReaderIndex();
        }

        private int sendNextBatch(ClientPacketListener connection)
        {
            int sentThisBatch = 0;
            long deadline = Util.getNanos() + SERVUX_PACKET_SEND_BUDGET_NANOS;

            while (!this.isComplete() &&
                    sentThisBatch < SERVUX_PACKET_SLICES_PER_TICK &&
                    Util.getNanos() < deadline)
            {
                int length = Math.min(PacketSplitter.MAX_PAYLOAD_PER_PACKET_C2S, this.encodedBytes - this.sentBytes);
                FriendlyByteBuf slice = new FriendlyByteBuf(Unpooled.buffer(length + (this.sentBytes == 0 ? 5 : 0)));

                if (this.sentBytes == 0)
                {
                    slice.writeVarInt(this.encodedBytes);
                }

                slice.writeBytes(this.buffer, length);

                try
                {
                    ServuxLitematicaHandler.getInstance().encodeWithSplitter(slice, connection);
                }
                finally
                {
                    slice.release();
                }

                this.sentBytes += length;
                this.sentSlices++;
                sentThisBatch++;
            }

            return sentThisBatch;
        }

        private boolean isComplete()
        {
            return this.sentBytes >= this.encodedBytes;
        }

        private void release()
        {
            if (!this.released)
            {
                this.buffer.release();
                this.released = true;
            }
        }

        private int nbtBytes()
        {
            return this.nbtBytes;
        }

        private int maxInlineBytes()
        {
            return this.maxInlineBytes;
        }

        private int encodedBytes()
        {
            return this.encodedBytes;
        }

        private int sentBytes()
        {
            return this.sentBytes;
        }

        private int totalSlices()
        {
            return this.totalSlices;
        }

        private int sentSlices()
        {
            return this.sentSlices;
        }
    }

    private static final class ClientSchematicShadowSync
    {
        private final ClientLevel clientLevel;
        private final LitematicaSchematic schematic;
        private final BlockPos origin;
        private final List<String> regionNames;
        private final LongOpenHashSet renderSections = new LongOpenHashSet();
        private final LongOpenHashSet renderChunks = new LongOpenHashSet();
        private int regionIndex;
        private int x;
        private int y;
        private int z;
        private long processedVolume;
        private long totalVolume;
        private int pastedBlocks;
        private int changedBlocks;
        private int skippedStructureVoid;
        private int skippedUnloaded;

        private ClientSchematicShadowSync(ClientLevel clientLevel, LitematicaSchematic schematic, BlockPos origin)
        {
            this.clientLevel = Objects.requireNonNull(clientLevel, "clientLevel");
            this.schematic = Objects.requireNonNull(schematic, "schematic");
            this.origin = Objects.requireNonNull(origin, "origin");
            this.regionNames = List.copyOf(schematic.getAreas().keySet());

            for (String regionName : this.regionNames)
            {
                LitematicaBlockStateContainer container = Objects.requireNonNull(
                        schematic.getSubRegionContainer(regionName), "subRegionContainer");
                Vec3i size = container.getSize();
                this.totalVolume += (long) size.getX() * (long) size.getY() * (long) size.getZ();
            }
        }

        private boolean processNextBatch()
        {
            long deadline = Util.getNanos() + SERVUX_CLIENT_SYNC_BUDGET_NANOS;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

            while (this.regionIndex < this.regionNames.size() && Util.getNanos() < deadline)
            {
                String regionName = this.regionNames.get(this.regionIndex);
                LitematicaBlockStateContainer container = Objects.requireNonNull(
                        this.schematic.getSubRegionContainer(regionName), "subRegionContainer");
                BlockPos regionPos = Objects.requireNonNull(this.schematic.getSubRegionPosition(regionName),
                        "subRegionPosition");
                Vec3i size = container.getSize();

                while (this.y < size.getY() && Util.getNanos() < deadline)
                {
                    BlockState targetState = container.get(this.x, this.y, this.z);
                    this.processedVolume++;

                    if (targetState.is(Blocks.STRUCTURE_VOID))
                    {
                        this.skippedStructureVoid++;
                    }
                    else
                    {
                        this.applyTargetBlock(regionPos, targetState, mutable);
                    }

                    this.advance(size);
                }

                if (this.y >= size.getY())
                {
                    this.regionIndex++;
                    this.x = 0;
                    this.y = 0;
                    this.z = 0;
                }
            }

            return this.regionIndex >= this.regionNames.size();
        }

        private void applyTargetBlock(BlockPos regionPos, BlockState targetState, BlockPos.MutableBlockPos mutable)
        {
            int worldX = this.origin.getX() + regionPos.getX() + this.x;
            int worldY = this.origin.getY() + regionPos.getY() + this.y;
            int worldZ = this.origin.getZ() + regionPos.getZ() + this.z;
            int sectionX = SectionPos.blockToSectionCoord(worldX);
            int sectionY = SectionPos.blockToSectionCoord(worldY);
            int sectionZ = SectionPos.blockToSectionCoord(worldZ);

            if (!this.clientLevel.hasChunk(sectionX, sectionZ))
            {
                this.skippedUnloaded++;
                return;
            }

            mutable.set(worldX, worldY, worldZ);
            this.pastedBlocks++;
            this.renderSections.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
            this.renderChunks.add(chunkKey(sectionX, sectionZ));

            BlockState currentState = this.clientLevel.getBlockState(mutable);

            if (!statesEquivalent(currentState, targetState))
            {
                this.clientLevel.setBlock(mutable, targetState, CLIENT_SHADOW_SET_FLAGS);
                this.changedBlocks++;
            }
        }

        private void advance(Vec3i size)
        {
            this.x++;

            if (this.x >= size.getX())
            {
                this.x = 0;
                this.z++;
            }

            if (this.z >= size.getZ())
            {
                this.z = 0;
                this.y++;
            }
        }

        private void refreshRenderState()
        {
            LongIterator sectionIterator = this.renderSections.iterator();

            while (sectionIterator.hasNext())
            {
                long section = sectionIterator.nextLong();
                this.clientLevel.setSectionDirtyWithNeighbors(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            }

            LongIterator chunkIterator = this.renderChunks.iterator();

            while (chunkIterator.hasNext())
            {
                long chunk = chunkIterator.nextLong();
                SchematicWorldRefresher.INSTANCE.markSchematicChunksForRenderUpdate(chunkKeyX(chunk), chunkKeyZ(chunk));
            }
        }

        private static long chunkKey(int x, int z)
        {
            return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
        }

        private static int chunkKeyX(long key)
        {
            return (int) (key & 0xFFFFFFFFL);
        }

        private static int chunkKeyZ(long key)
        {
            return (int) (key >>> 32);
        }

        private static boolean statesEquivalent(BlockState currentState, BlockState targetState)
        {
            return currentState.equals(targetState) || (currentState.isAir() && targetState.isAir());
        }

        private int regionCount()
        {
            return this.regionNames.size();
        }

        private long processedVolume()
        {
            return this.processedVolume;
        }

        private long totalVolume()
        {
            return this.totalVolume;
        }

        private int pastedBlocks()
        {
            return this.pastedBlocks;
        }

        private int changedBlocks()
        {
            return this.changedBlocks;
        }

        private int skippedStructureVoid()
        {
            return this.skippedStructureVoid;
        }

        private int skippedUnloaded()
        {
            return this.skippedUnloaded;
        }

        private int renderSectionCount()
        {
            return this.renderSections.size();
        }

        private int renderChunkCount()
        {
            return this.renderChunks.size();
        }
    }

    public enum Mode
    {
        CHECKOUT("LVC Checkout", LvcOperationJournal.Operation.CHECKOUT, "checkout"),
        CHECKOUT_BRANCH("LVC Checkout Branch", LvcOperationJournal.Operation.CHECKOUT, "checkout"),
        DISCARD("LVC Discard Changes", LvcOperationJournal.Operation.DISCARD, "discard"),
        CLEAR("LVC Clear Area", LvcOperationJournal.Operation.CLEAR, "clear"),
        DELETE_VERSION("LVC Delete Version", LvcOperationJournal.Operation.DELETE_VERSION, "restore"),
        MERGE("LVC Merge Branch", LvcOperationJournal.Operation.MERGE, "restore");

        private final String displayName;
        private final LvcOperationJournal.Operation journalOperation;
        private final String journalPhase;

        Mode(String displayName, LvcOperationJournal.Operation journalOperation, String journalPhase)
        {
            this.displayName = displayName;
            this.journalOperation = journalOperation;
            this.journalPhase = journalPhase;
        }
    }

    private enum Phase
    {
        REQUEST_SERVUX_DATA("read server data"),
        BUILD("build target"),
        PREPARE_SERVUX_PAYLOAD("prepare servux payload"),
        WRITE_JOURNAL("prepare Git"),
        CLEAR_ENTITIES("void entity cleanup"),
        CLEAR_FURNACE_XP("clear furnace xp"),
        SEND_PASTE("send paste"),
        WAIT_COMMANDS("wait commands"),
        SYNC_CLIENT("sync client"),
        FINISH("finish"),
        DONE("done");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }

    public record Result(Mode mode, LvcWorldBackend backend, int regionCount, boolean lossy,
                         boolean commandPasteScheduled)
    {
    }

    private static final class CommandPasteConfigOverride
    {
        private final IConfigOptionListEntry replaceBehavior;
        private final IConfigOptionListEntry layerBehavior;
        private final IConfigOptionListEntry nbtBehavior;
        private final boolean ignoreEntities;
        private boolean restored;

        private CommandPasteConfigOverride(IConfigOptionListEntry replaceBehavior,
                                           IConfigOptionListEntry layerBehavior,
                                           IConfigOptionListEntry nbtBehavior,
                                           boolean ignoreEntities)
        {
            this.replaceBehavior = replaceBehavior;
            this.layerBehavior = layerBehavior;
            this.nbtBehavior = nbtBehavior;
            this.ignoreEntities = ignoreEntities;
        }

        private static CommandPasteConfigOverride apply()
        {
            CommandPasteConfigOverride previous = new CommandPasteConfigOverride(
                    Configs.Generic.PASTE_REPLACE_BEHAVIOR.getOptionListValue(),
                    Configs.Generic.PASTE_LAYER_BEHAVIOR.getOptionListValue(),
                    Configs.Generic.PASTE_NBT_BEHAVIOR.getOptionListValue(),
                    Configs.Generic.PASTE_IGNORE_ENTITIES.getBooleanValue()
            );
            Configs.Generic.PASTE_REPLACE_BEHAVIOR.setOptionListValue(ReplaceBehavior.ALL);
            Configs.Generic.PASTE_LAYER_BEHAVIOR.setOptionListValue(PasteLayerBehavior.ALL);
            Configs.Generic.PASTE_NBT_BEHAVIOR.setOptionListValue(PasteNbtBehavior.NONE);
            Configs.Generic.PASTE_IGNORE_ENTITIES.setBooleanValue(true);
            return previous;
        }

        private void restore()
        {
            if (this.restored)
            {
                return;
            }

            Configs.Generic.PASTE_REPLACE_BEHAVIOR.setOptionListValue(this.replaceBehavior);
            Configs.Generic.PASTE_LAYER_BEHAVIOR.setOptionListValue(this.layerBehavior);
            Configs.Generic.PASTE_NBT_BEHAVIOR.setOptionListValue(this.nbtBehavior);
            Configs.Generic.PASTE_IGNORE_ENTITIES.setBooleanValue(this.ignoreEntities);
            this.restored = true;
        }
    }
}
