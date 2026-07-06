package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
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
import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkCommand;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.util.PasteNbtBehavior;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import me.zly2006.lvc.LvcDiagnostics;
import me.zly2006.lvc.capture.LvcSiteWorkPlan;
import me.zly2006.lvc.capture.LvcWorldReader;
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcChunk;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.semantic.LvcTrackedBlockCursor;
import me.zly2006.lvc.storage.LvcChunkCodec;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.world.LvcWorldBackend;

public final class LvcRemoteServerApplyTask extends LvcChunkedTaskBase<LvcRemoteServerApplyTask.Result>
{
    private static final long DROP_CLEANUP_DELAY_MS = 1_000L;
    private static final int CLEANUP_COMMANDS_PER_TICK = 4;

    private final Path repositoryDirectory;
    private final Level world;
    private final Mode mode;
    @Nullable private final String targetCommitId;
    @Nullable private final String targetBranchName;
    @Nullable private final List<BlockPos> furnaceXpCleanupCandidates;

    @Nullable private LvcWorldBackend backend;
    @Nullable private Git git;
    @Nullable private RevWalk revWalk;
    @Nullable private RevCommit targetCommit;
    @Nullable private LvcSemanticSchematicBuilder.BuildSession buildSession;
    @Nullable private LitematicaSchematic schematic;
    @Nullable private LvcManifest manifest;
    @Nullable private LvcLocalState localState;
    @Nullable private String siteId;
    @Nullable private LvcLocalState.SitePlacement placement;
    @Nullable private LvcSiteWorkPlan cleanupPlan;
    @Nullable private BlockPos origin;
    @Nullable private Result result;
    @Nullable private CommandPasteConfigOverride commandConfigOverride;
    @Nullable private SchematicPlacement commandPlacement;
    @Nullable private LitematicaSchematic commandSchematic;
    @Nullable private LvcWorldReader sparseTargetReader;
    private final List<BlockPos> sparseFurnaceXpCleanupCandidates = new ArrayList<>();
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
    private boolean dropCleanupPrepared;
    private long dropCleanupReadyAtMillis;
    private int furnaceXpCleanupChunkIndex;
    private int furnaceXpCleanupCommandsPrepared;
    private int sparseTargetScannedBlocks;
    private int sparseTargetStateMismatches;
    private int sparseTargetBlockEntityMismatches;
    private final Deque<String> cleanupCommandQueue = new ArrayDeque<>();

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
                targetBranchName, furnaceXpCleanupCandidates, callbacks);
    }

    public static LvcRemoteServerApplyTask discard(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                   @Nullable String targetCommitId,
                                                   LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.DISCARD, targetCommitId,
                null, null, callbacks);
    }

    public static LvcRemoteServerApplyTask clear(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                 LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.CLEAR, null, null, null, callbacks);
    }

    public static LvcRemoteServerApplyTask deleteVersion(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                         String targetCommitId, String targetBranchName,
                                                         LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.DELETE_VERSION, targetCommitId,
                targetBranchName, null, callbacks);
    }

    private LvcRemoteServerApplyTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                     Mode mode, @Nullable String targetCommitId,
                                     @Nullable String targetBranchName,
                                     @Nullable List<BlockPos> furnaceXpCleanupCandidates,
                                     LvcTaskCallbacks<Result> callbacks)
    {
        super(handle, mode.displayName, callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.targetCommitId = normalize(targetCommitId);
        this.targetBranchName = normalize(targetBranchName);
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

            this.validateRemoteBackendReady();
            this.localState = LvcSemanticRepository.readLocalState(this.repositoryDirectory);
            this.siteId = this.localState.activeSite();

            if (this.mode == Mode.CLEAR)
            {
                this.manifest = LvcSemanticRepository.readManifest(this.repositoryDirectory);
                this.preparePlacementState();
                this.schematic = LvcSemanticSchematicBuilder.buildAirSchematic(this.manifest, this.localState, this.siteId);
                this.phase = Phase.WRITE_JOURNAL;
            }
            else
            {
                this.git = Git.open(this.repositoryDirectory.toFile());
                Repository repository = this.git.getRepository();
                this.revWalk = new RevWalk(repository);
                this.capturePreviousHead(repository);
                this.targetCommit = this.resolveTargetCommit(repository);
                this.manifest = LvcSemanticRepository.readCommitManifest(repository, this.targetCommit);
                this.preparePlacementState();
                this.sparseTargetReader = this.shouldBuildSparseTargetSchematic() ? this.requireBackend().createReader(this.world) : null;
                this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                        this.manifest,
                        this.localState,
                        this.siteId,
                        objectId -> this.readCommitObject(this.requireTargetCommit(), objectId),
                        null,
                        this.sparseTargetReader == null ? null : this::shouldIncludeSparseTargetBlock
                );
                this.phase = Phase.BUILD;
            }

            LvcDiagnostics.debug(this.handle(),
                    "remote server apply initialized mode={} backend={} lossy={} site={} target={} branch='{}' regions={} chunks={} dimension={} origin={} sparseTarget={}",
                    this.mode.name(), this.backend.id(), this.backend.lossy(), this.siteId,
                    this.targetCommit == null ? "<none>" : this.targetCommit.getName(),
                    this.targetBranchName == null ? "<none>" : this.targetBranchName,
                    this.regionCount, this.buildSession == null ? 0 : this.buildSession.totalChunks(),
                    this.requirePlacement().dimension(), this.requirePlacement().origin(),
                    this.sparseTargetReader != null);
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
        if (this.phase == Phase.BUILD)
        {
            LvcSemanticSchematicBuilder.BuildSession session = this.requireBuildSession();

            if (!session.isComplete())
            {
                session.processNextChunk();
                return false;
            }

            this.schematic = session.result();
            LvcDiagnostics.debug(this.handle(),
                    "remote server apply target schematic built sparse={} chunks={} includedBlocks={} structureVoidBlocks={} scannedBlocks={} stateMismatches={} blockEntityMismatches={}",
                    this.sparseTargetReader != null, session.totalChunks(), session.includedBlocks(),
                    session.structureVoidBlocks(), this.sparseTargetScannedBlocks,
                    this.sparseTargetStateMismatches, this.sparseTargetBlockEntityMismatches);
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
                this.dropCleanupReadyAtMillis = Util.getMillis() + DROP_CLEANUP_DELAY_MS;
                this.phase = Phase.WAIT_ENTITY_DROPS;
            }

            return false;
        }

        if (this.phase == Phase.WAIT_ENTITY_DROPS)
        {
            if (Util.getMillis() < this.dropCleanupReadyAtMillis)
            {
                return false;
            }

            this.phase = Phase.CLEAR_DROPS;
            return false;
        }

        if (this.phase == Phase.CLEAR_DROPS)
        {
            this.prepareEntityDropCleanupCommands();

            if (this.sendQueuedCleanupCommands())
            {
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
            this.sendRemotePaste();
            this.phase = this.commandPasteScheduled ? Phase.WAIT_COMMANDS : Phase.FINISH;
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

        if (this.phase == Phase.BUILD && this.buildSession != null)
        {
            this.infoHudLines.add(String.format(Locale.ROOT, "Chunks: %d / %d",
                    this.buildSession.processedChunks(), this.buildSession.totalChunks()));
        }
        else if (this.phase == Phase.WAIT_ENTITY_DROPS)
        {
            long remainingMs = Math.max(0L, this.dropCleanupReadyAtMillis - Util.getMillis());
            this.infoHudLines.add(String.format(Locale.ROOT, "Drop cleanup: %.1fs",
                    remainingMs / 1000.0D));
        }
        else if ((this.phase == Phase.CLEAR_ENTITIES || this.phase == Phase.CLEAR_DROPS ||
                this.phase == Phase.CLEAR_FURNACE_XP) &&
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
    }

    private void preparePlacementState() throws IOException
    {
        LvcManifest currentManifest = Objects.requireNonNull(this.manifest, "manifest");
        LvcLocalState currentLocalState = Objects.requireNonNull(this.localState, "localState");
        String currentSiteId = Objects.requireNonNull(this.siteId, "siteId");
        LvcManifest.Site site = currentManifest.site(currentSiteId);
        LvcLocalState.SitePlacement sitePlacement = currentLocalState.sites().get(currentSiteId);

        if (sitePlacement == null)
        {
            throw new IOException("Missing local placement for LVC site: " + currentSiteId);
        }

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

    private boolean shouldBuildSparseTargetSchematic()
    {
        return this.requireBackend() == LvcWorldBackend.SERVUX && this.mode != Mode.CLEAR;
    }

    private boolean shouldIncludeSparseTargetBlock(LvcSemanticSchematicBuilder.TargetBlock block,
                                                   BlockState targetState) throws IOException
    {
        LvcWorldReader reader = Objects.requireNonNull(this.sparseTargetReader, "sparseTargetReader");

        if (!reader.canReadAt(block.worldPos()))
        {
            throw new IOException("LVC remote sparse target diff cannot read tracked block at " + block.blockPos());
        }

        this.sparseTargetScannedBlocks++;
        String currentBlockState = LvcChunkCodec.canonicalTrackedBlockState(reader.blockStateAt(block.worldPos()));
        String targetBlockState = LvcChunkCodec.canonicalTrackedBlockState(block.blockState());

        if (!Objects.equals(currentBlockState, targetBlockState))
        {
            this.sparseTargetStateMismatches++;
            this.addSparseFurnaceXpCleanupCandidateIfNeeded(currentBlockState, block.blockPos());
            return true;
        }

        byte[] targetBlockEntity = block.blockEntityBytes();

        if (targetBlockEntity != null)
        {
            byte[] currentBlockEntity = reader.blockEntityNbtAt(block.worldPos());

            if (!Arrays.equals(currentBlockEntity, targetBlockEntity))
            {
                this.sparseTargetBlockEntityMismatches++;
                this.addSparseFurnaceXpCleanupCandidateIfNeeded(currentBlockState, block.blockPos());
                return true;
            }
        }

        return false;
    }

    private void addSparseFurnaceXpCleanupCandidateIfNeeded(String currentBlockState, BlockPos pos)
    {
        if (isFurnaceLikeBlockState(currentBlockState))
        {
            this.sparseFurnaceXpCleanupCandidates.add(pos);
        }
    }

    private void sendRemotePaste() throws Exception
    {
        LitematicaSchematic currentSchematic = Objects.requireNonNull(this.schematic, "schematic");
        LvcWorldBackend currentBackend = this.requireBackend();

        if (currentBackend == LvcWorldBackend.SERVUX)
        {
            this.sendServuxPaste(currentSchematic);
            LvcDiagnostics.info(this.handle(), "remote server apply sent Servux paste mode={} regions={} target={}",
                    this.mode.name(), this.regionCount, this.targetCommitName());
        }
        else
        {
            this.stripUnsupportedCommandPayloads(currentSchematic);
            this.scheduleCommandPaste(currentSchematic);
            LvcDiagnostics.info(this.handle(), "remote server apply scheduled command paste mode={} regions={} target={}",
                    this.mode.name(), this.regionCount, this.targetCommitName());
        }
    }

    private void validateRemoteBackendReady() throws IOException
    {
        if (this.hasGamemasterCommandPermission())
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

        if (this.requireBackend() == LvcWorldBackend.SERVUX)
        {
            throw new IOException("Remote Servux apply requires gamemaster command permission to clear entities before paste.");
        }

        throw new IOException("Remote command fallback requires gamemaster command permission on the server." + hint);
    }

    private boolean hasGamemasterCommandPermission()
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

    private void sendServuxPaste(LitematicaSchematic currentSchematic) throws IOException
    {
        SchematicPlacement pastePlacement = SchematicPlacement.createFor(currentSchematic, this.requireOrigin(),
                this.remotePlacementName(), true, false);
        CompoundTag nbt = pastePlacement.toNbt(true);
        nbt.putString("Task", "LitematicaPaste");
        nbt.putString("ReplaceMode", ReplaceBehavior.ALL.getStringValue());
        nbt.putString("PasteLayerBehavior", PasteLayerBehavior.ALL.getStringValue());

        int maxSize = PacketSplitter.DEFAULT_MAX_RECEIVE_SIZE_S2C - 4096;
        int nbtBytes = nbt.sizeInBytes();
        boolean useFileBackedTransfer = nbtBytes > maxSize;

        LvcDiagnostics.debug(this.handle(), "remote Servux paste prepared nbtBytes={} maxInlineBytes={} fileBacked={}",
                nbtBytes, maxSize, useFileBackedTransfer);

        if (useFileBackedTransfer)
        {
            LitematicaSchematic fileBacked = this.writeAndReloadTempSchematic(currentSchematic);
            nbt.remove("Schematics");
            fileBacked.sendTransmitFile(nbt, RandomSource.create(Util.getMillis()).nextLong(), false);
            return;
        }

        ServuxLitematicaHandler.getInstance().encodeClientData(
                ServuxLitematicaPacket.ResponseC2SStart(nbt));
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
        TaskPasteSchematicPerChunkCommand task = new TaskPasteSchematicPerChunkCommand(List.of(pastePlacement), range, false);
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

        int commands = this.enqueueBoundedEntityKillCommands("type=!player");
        this.entityCleanupPrepared = true;

        LvcDiagnostics.debug(this.handle(), "remote server apply prepared bounded entity cleanup commands regions={} commands={} batchSize={}",
                this.regionCount, commands, CLEANUP_COMMANDS_PER_TICK);
    }

    private void prepareEntityDropCleanupCommands()
    {
        if (this.dropCleanupPrepared)
        {
            return;
        }

        int commands = this.enqueueBoundedEntityKillCommands("type=minecraft:item");
        commands += this.enqueueBoundedEntityKillCommands("type=minecraft:experience_orb");
        this.dropCleanupPrepared = true;

        LvcDiagnostics.debug(this.handle(), "remote server apply prepared bounded drop cleanup commands regions={} commands={} delayMs={} batchSize={}",
                this.regionCount, commands, DROP_CLEANUP_DELAY_MS, CLEANUP_COMMANDS_PER_TICK);
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
            return this.sparseFurnaceXpCleanupCandidates;
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

    private static boolean isFurnaceLikeBlockState(String blockState)
    {
        return blockState.equals("minecraft:furnace") || blockState.startsWith("minecraft:furnace[") ||
                blockState.equals("minecraft:blast_furnace") || blockState.startsWith("minecraft:blast_furnace[") ||
                blockState.equals("minecraft:smoker") || blockState.startsWith("minecraft:smoker[");
    }

    private int enqueueBoundedEntityKillCommands(String selectorFilter)
    {
        BlockPos siteOrigin = this.requireOrigin();
        int commands = 0;

        for (LvcManifest.Region region : this.requireManifest().site(this.requireSiteId()).regions())
        {
            BlockPos min = siteOrigin.offset(LvcProjectPositions.blockPosFromList(region.min()));
            BlockPos size = LvcProjectPositions.blockPosFromList(region.size());
            String command = String.format(Locale.ROOT,
                    "kill @e[%s,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                    selectorFilter,
                    min.getX(), min.getY(), min.getZ(),
                    Math.max(0, size.getX() - 1), Math.max(0, size.getY() - 1), Math.max(0, size.getZ() - 1));
            this.cleanupCommandQueue.addLast(command);
            commands++;
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

    private LvcLocalState.SitePlacement requirePlacement()
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

    public enum Mode
    {
        CHECKOUT("LVC Checkout", LvcOperationJournal.Operation.CHECKOUT, "checkout"),
        CHECKOUT_BRANCH("LVC Checkout Branch", LvcOperationJournal.Operation.CHECKOUT, "checkout"),
        DISCARD("LVC Discard Changes", LvcOperationJournal.Operation.DISCARD, "discard"),
        CLEAR("LVC Clear Area", LvcOperationJournal.Operation.CLEAR, "clear"),
        DELETE_VERSION("LVC Delete Version", LvcOperationJournal.Operation.DELETE_VERSION, "restore");

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
        BUILD("build target"),
        WRITE_JOURNAL("prepare Git"),
        CLEAR_ENTITIES("clear entities"),
        WAIT_ENTITY_DROPS("wait drops"),
        CLEAR_DROPS("clear drops"),
        CLEAR_FURNACE_XP("clear furnace xp"),
        SEND_PASTE("send paste"),
        WAIT_COMMANDS("wait commands"),
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
