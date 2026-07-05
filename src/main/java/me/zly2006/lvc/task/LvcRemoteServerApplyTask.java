package me.zly2006.lvc.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
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
import me.zly2006.lvc.git.LvcProjectGitOps;
import me.zly2006.lvc.model.LvcLocalState;
import me.zly2006.lvc.model.LvcManifest;
import me.zly2006.lvc.project.LvcProjectPositions;
import me.zly2006.lvc.semantic.LvcSemanticSchematicBuilder;
import me.zly2006.lvc.storage.LvcChunkStore;
import me.zly2006.lvc.storage.LvcSemanticRepository;
import me.zly2006.lvc.world.LvcWorldBackend;

public final class LvcRemoteServerApplyTask extends LvcChunkedTaskBase<LvcRemoteServerApplyTask.Result>
{
    private final Path repositoryDirectory;
    private final Level world;
    private final Mode mode;
    @Nullable private final String targetCommitId;
    @Nullable private final String targetBranchName;

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
    @Nullable private BlockPos origin;
    @Nullable private Result result;
    @Nullable private CommandPasteConfigOverride commandConfigOverride;
    @Nullable private SchematicPlacement commandPlacement;
    @Nullable private LitematicaSchematic commandSchematic;
    @Nullable private String previousHead;
    @Nullable private String previousBranch;
    private Phase phase = Phase.BUILD;
    private int regionCount;
    private boolean commandPasteScheduled;
    private boolean commandPasteFinished;
    private boolean commandPasteSucceeded;
    private boolean journalWritten;
    private boolean gitMoved;

    public static LvcRemoteServerApplyTask checkout(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                    String targetCommitId, @Nullable String targetBranchName,
                                                    LvcTaskCallbacks<Result> callbacks)
    {
        Mode mode = targetBranchName == null || targetBranchName.isBlank() ? Mode.CHECKOUT : Mode.CHECKOUT_BRANCH;
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, mode, targetCommitId,
                targetBranchName, callbacks);
    }

    public static LvcRemoteServerApplyTask discard(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                   @Nullable String targetCommitId,
                                                   LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.DISCARD, targetCommitId,
                null, callbacks);
    }

    public static LvcRemoteServerApplyTask clear(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                                 LvcTaskCallbacks<Result> callbacks)
    {
        return new LvcRemoteServerApplyTask(handle, repositoryDirectory, world, Mode.CLEAR, null, null, callbacks);
    }

    private LvcRemoteServerApplyTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                     Mode mode, @Nullable String targetCommitId,
                                     @Nullable String targetBranchName, LvcTaskCallbacks<Result> callbacks)
    {
        super(handle, mode.displayName, callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.targetCommitId = normalize(targetCommitId);
        this.targetBranchName = normalize(targetBranchName);
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
                this.buildSession = LvcSemanticSchematicBuilder.beginSchematicBuild(
                        this.manifest,
                        this.localState,
                        this.siteId,
                        objectId -> this.readCommitObject(this.requireTargetCommit(), objectId)
                );
                this.phase = Phase.BUILD;
            }

            LvcDiagnostics.debug(this.handle(),
                    "remote server apply initialized mode={} backend={} lossy={} site={} target={} branch='{}' regions={} chunks={} dimension={} origin={}",
                    this.mode.name(), this.backend.id(), this.backend.lossy(), this.siteId,
                    this.targetCommit == null ? "<none>" : this.targetCommit.getName(),
                    this.targetBranchName == null ? "<none>" : this.targetBranchName,
                    this.regionCount, this.buildSession == null ? 0 : this.buildSession.totalChunks(),
                    this.requirePlacement().dimension(), this.requirePlacement().origin());
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
            this.sendEntityCleanupCommands();
            this.phase = Phase.SEND_PASTE;
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
        this.origin = LvcProjectPositions.blockPosFromList(sitePlacement.origin());
        this.regionCount = site.regions().size();

        if (this.regionCount == 0)
        {
            throw new IOException("LVC project has no tracked sub-regions");
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
        if (this.requireBackend() != LvcWorldBackend.COMMANDS || this.hasGamemasterCommandPermission())
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

    private void sendEntityCleanupCommands()
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null)
        {
            return;
        }

        BlockPos siteOrigin = this.requireOrigin();

        for (LvcManifest.Region region : this.requireManifest().site(this.requireSiteId()).regions())
        {
            BlockPos min = siteOrigin.offset(LvcProjectPositions.blockPosFromList(region.min()));
            BlockPos size = LvcProjectPositions.blockPosFromList(region.size());
            String command = String.format(Locale.ROOT,
                    "kill @e[type=!player,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                    min.getX(), min.getY(), min.getZ(),
                    Math.max(0, size.getX() - 1), Math.max(0, size.getY() - 1), Math.max(0, size.getZ() - 1));
            minecraft.player.connection.sendCommand(command);
        }

        LvcDiagnostics.debug(this.handle(), "remote server apply queued entity cleanup commands regions={}",
                this.regionCount);
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
        CLEAR("LVC Clear Area", LvcOperationJournal.Operation.CLEAR, "clear");

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
