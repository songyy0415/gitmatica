package me.arnavpmr.lvc.task;

import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.overlay.LvcTrackingOverlayService;
import me.arnavpmr.lvc.semantic.LvcProjectCreationResult;
import me.arnavpmr.lvc.project.LvcProjectSelectionStorage;
import me.arnavpmr.lvc.project.LvcProjectPaths;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import org.eclipse.jgit.revwalk.RevCommit;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.LvcPlayerIdentity;
import me.arnavpmr.lvc.capture.LvcCaptureEngine;
import me.arnavpmr.lvc.capture.LvcCaptureSession;
import me.arnavpmr.lvc.capture.LvcMinecraftWorldReader;
import me.arnavpmr.lvc.capture.LvcSiteWorkPlan;
import me.arnavpmr.lvc.model.LvcManifest;
import me.arnavpmr.lvc.model.LvcSitePlacement;
import me.arnavpmr.lvc.storage.LvcChunkCodec;
import me.arnavpmr.lvc.storage.LvcChunkStagingStore;
import me.arnavpmr.lvc.world.LvcWorldBackend;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticInitProjectTask extends LvcChunkedTaskBase<LvcProjectCreationResult>
{
    private final Path gameRunDirectory;
    private final String repositoryName;
    private final LvcPlayerIdentity player;
    private final Level world;
    private final AreaSelection selection;
    @Nullable private Path repositoryDirectory;
    @Nullable private LvcManifest.Site site;
    @Nullable private LvcSitePlacement placement;
    @Nullable private LvcChunkStagingStore stagingStore;
    @Nullable private LvcCaptureSession captureSession;
    @Nullable private LvcCaptureEngine.Result captureResult;
    @Nullable private LvcWorldBackend backend;
    @Nullable private LvcServuxBulkRequestPlanner servuxRequests;
    private boolean waitingForServux;
    private List<String> publishObjectIds = List.of();
    private Phase phase = Phase.CAPTURE;
    private int nextPublishIndex;
    private boolean repositoryCreated;
    private boolean committed;
    @Nullable private RevCommit commit;

    public LvcSemanticInitProjectTask(LvcOperationHandle handle, Path gameRunDirectory, String repositoryName,
                                      LvcPlayerIdentity player, Level world, AreaSelection selection,
                                      LvcTaskCallbacks<LvcProjectCreationResult> callbacks)
    {
        super(handle, "LVC Create Project", callbacks, true);
        this.gameRunDirectory = Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @Override
    public void init()
    {
        try
        {
            String displayName = LvcProjectPaths.normalizeDisplayName(this.repositoryName);
            int validBoxCount = LvcProjectSelectionStorage.countValidSelectionRegions(this.selection);

            if (validBoxCount <= 0)
            {
                throw new IllegalArgumentException("LVC project requires at least one valid selection box");
            }

            this.repositoryDirectory = LvcProjectPaths.repositoryDirectory(this.gameRunDirectory, displayName);

            if (Files.exists(this.repositoryDirectory))
            {
                if (this.cleanupInterruptedInitDirectory(this.repositoryDirectory))
                {
                    Files.createDirectories(this.repositoryDirectory);
                }
                else
                {
                    throw new FileAlreadyExistsException(this.repositoryDirectory.toString());
                }
            }
            else
            {
                Files.createDirectories(this.repositoryDirectory);
            }

            if (!Files.isDirectory(this.repositoryDirectory))
            {
                throw new FileAlreadyExistsException(this.repositoryDirectory.toString());
            }

            this.repositoryCreated = true;

            String dimensionId = LvcMinecraftWorldReader.dimensionId(this.world);
            this.site = LvcProjectSelectionStorage.createMainSiteFromSelection(displayName, dimensionId, this.selection);
            this.placement = LvcProjectSelectionStorage.createSitePlacement(this.selection.getEffectiveOrigin(), dimensionId);
            this.backend = LvcWorldBackend.resolve(this.world);
            LvcDiagnostics.debug(this.handle(), "semantic init prepared repo='{}' project='{}' dimension='{}' regions={} origin='{}' backend={} lossy={} blockEntities={} entities={}",
                    this.repositoryDirectory, displayName, dimensionId, validBoxCount, this.placement.origin(),
                    this.backend.id(), this.backend.lossy(), this.backend.capturesBlockEntities(),
                    this.backend.capturesEntities());
            this.stagingStore = new LvcChunkStagingStore(
                    this.repositoryDirectory,
                    LvcOperationJournal.stagingDirectory(this.repositoryDirectory, this.handle())
            );
            LvcSiteWorkPlan plan = LvcSiteWorkPlan.create(this.site, this.placement);
            this.captureSession = new LvcCaptureSession(
                    plan,
                    this.backend.createReader(this.world),
                    this.stagingStore::writeObject,
                    false,
                    true
            );
            this.servuxRequests = this.backend == LvcWorldBackend.SERVUX ? LvcServuxBulkRequestPlanner.create(plan) : null;
            LvcDiagnostics.debug(this.handle(), "semantic init capture planned chunks={} servuxColumns={}",
                    this.captureSession.totalChunks(), this.servuxRequests == null ? 0 : this.servuxRequests.totalColumns());
            LvcOperationJournal.write(this.repositoryDirectory, LvcOperationJournal.Operation.INIT, null, "capture");
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
        if (this.phase == Phase.CAPTURE)
        {
            LvcCaptureSession session = this.requireCaptureSession();

            if (!session.isComplete())
            {
                if (!this.waitForServuxChunkIfNeeded(session))
                {
                    return false;
                }

                session.processNextChunk();
                return false;
            }

            this.captureResult = session.result();
            this.publishObjectIds = new ArrayList<>(this.captureResult.fullHashes().values());
            LvcDiagnostics.debug(this.handle(), "semantic init capture completed chunks={} objects={} canonicalBytes={} storedBytes={} blockEntityReadAttempts={} blockEntityRecords={} chunkFormat={} storageMode={} scheduledTicksStored=false",
                    session.totalChunks(), this.publishObjectIds.size(), session.fullHashContentBytes(), session.storedObjectBytes(),
                    session.blockEntityReadAttempts(), session.blockEntityRecords(),
                    LvcManifest.CHUNK_FORMAT, LvcChunkCodec.STORAGE_MODE);
            this.phase = Phase.PUBLISH;
            return false;
        }

        if (this.phase == Phase.PUBLISH)
        {
            if (this.nextPublishIndex < this.publishObjectIds.size())
            {
                this.requireStagingStore().publish(this.publishObjectIds.get(this.nextPublishIndex));
                this.nextPublishIndex++;
                return false;
            }

            this.phase = Phase.COMMIT;
            return false;
        }

        if (this.phase == Phase.COMMIT)
        {
            LvcSemanticRepository.CommitResult result = LvcSemanticRepository.initProjectFromCapture(
                    this.requireRepositoryDirectory(),
                    this.requireSite().name(),
                    this.requireSite(),
                    this.requirePlacement(),
                    Objects.requireNonNull(this.captureResult, "captureResult"),
                    this.player
            );
            this.commit = result.commit();
            this.committed = true;
            this.phase = Phase.DONE;
            return true;
        }

        return true;
    }

    @Override
    protected LvcProjectCreationResult result() throws Exception
    {
        this.requireStagingStore().cleanup();
        LvcOperationJournal.delete(this.requireRepositoryDirectory());
        LvcTrackingOverlayService.seedTrackingOverlayOrigin(this.requireRepositoryDirectory(), this.requirePlacement());
        return new LvcProjectCreationResult(this.requireRepositoryDirectory(), Objects.requireNonNull(this.commit, "commit").getName());
    }

    @Override
    protected boolean shouldContinueWithinTick()
    {
        return !this.waitingForServux;
    }

    @Override
    protected void updateProgressHud()
    {
        this.infoHudLines.clear();
        this.infoHudLines.add(GuiBase.TXT_WHITE + GuiBase.TXT_BOLD + this.getDisplayName() + GuiBase.TXT_RST);
        this.infoHudLines.add(StringUtils.translate("gitmatica.gui.label.lvc_project.task_phase", this.phase.label));

        if (this.phase == Phase.CAPTURE && this.captureSession != null)
        {
            this.infoHudLines.add(StringUtils.translate("gitmatica.gui.label.lvc_project.task_chunks", this.captureSession.processedChunks(), this.captureSession.totalChunks()));
        }
        else if (this.phase == Phase.PUBLISH)
        {
            this.infoHudLines.add(StringUtils.translate("gitmatica.gui.label.lvc_project.task_objects", this.nextPublishIndex, this.publishObjectIds.size()));
        }
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
            if (!this.committed)
            {
                this.cleanupUncommittedRepository();
            }
        }
    }

    private void cleanupUncommittedRepository()
    {
        try
        {
            if (this.stagingStore != null)
            {
                this.stagingStore.cleanup();
            }

            if (this.repositoryCreated && this.repositoryDirectory != null)
            {
                LvcOperationJournal.delete(this.repositoryDirectory);
                LvcChunkStagingStore.deleteRecursivelyIfExists(this.repositoryDirectory);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private boolean cleanupInterruptedInitDirectory(Path directory) throws Exception
    {
        LvcOperationJournal.Entry entry = LvcOperationJournal.read(directory);

        if (entry == null || !LvcOperationJournal.Operation.INIT.name().equals(entry.operation()))
        {
            return false;
        }

        LvcChunkStagingStore.deleteRecursivelyIfExists(directory);
        return true;
    }

    private LvcCaptureSession requireCaptureSession()
    {
        return Objects.requireNonNull(this.captureSession, "captureSession");
    }

    private LvcWorldBackend requireBackend()
    {
        return Objects.requireNonNull(this.backend, "backend");
    }

    private boolean waitForServuxChunkIfNeeded(LvcCaptureSession session)
    {
        if (this.requireBackend() != LvcWorldBackend.SERVUX)
        {
            this.waitingForServux = false;
            return true;
        }

        LvcServuxBulkRequestPlanner requests = Objects.requireNonNull(this.servuxRequests, "servuxRequests");
        boolean ready = requests.ensureReadyForCurrentChunk(session, this.handle(), "semantic init");
        this.waitingForServux = !ready;
        return ready;
    }

    private LvcChunkStagingStore requireStagingStore()
    {
        return Objects.requireNonNull(this.stagingStore, "stagingStore");
    }

    private Path requireRepositoryDirectory()
    {
        return Objects.requireNonNull(this.repositoryDirectory, "repositoryDirectory");
    }

    private LvcManifest.Site requireSite()
    {
        return Objects.requireNonNull(this.site, "site");
    }

    private LvcSitePlacement requirePlacement()
    {
        return Objects.requireNonNull(this.placement, "placement");
    }

    private enum Phase
    {
        CAPTURE("capture subchunks"),
        PUBLISH("publish objects"),
        COMMIT("write commit"),
        DONE("done");

        private final String label;

        Phase(String label)
        {
            this.label = label;
        }
    }
}
