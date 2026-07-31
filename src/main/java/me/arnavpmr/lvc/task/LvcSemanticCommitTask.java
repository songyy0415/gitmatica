package me.arnavpmr.lvc.task;

import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.semantic.LvcSemanticScanResult;
import me.arnavpmr.lvc.semantic.LvcSemanticScanMismatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import me.arnavpmr.lvc.storage.LvcChunkCodec;
import me.arnavpmr.lvc.storage.LvcChunkStagingStore;
import me.arnavpmr.lvc.world.LvcWorldBackend;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.StringUtils;

public final class LvcSemanticCommitTask extends LvcChunkedTaskBase<LvcSemanticCommitTask.Result>
{
    private static final int MISMATCH_SAMPLE_LIMIT = 8;

    private final Path repositoryDirectory;
    private final Level world;
    private final LvcPlayerIdentity player;
    private final String message;
    private final Mode mode;
    private final List<LvcManifest.Region> updatedRegions;
    @Nullable private LvcSemanticTaskContext.ActiveProject project;
    @Nullable private LvcManifest.Site captureSite;
    @Nullable private LvcChunkStagingStore stagingStore;
    @Nullable private LvcCaptureSession captureSession;
    @Nullable private LvcCaptureEngine.Result captureResult;
    @Nullable private LvcWorldBackend backend;
    @Nullable private LvcServuxBulkRequestPlanner servuxRequests;
    private boolean waitingForServux;
    private List<String> publishObjectIds = List.of();
    private Phase phase = Phase.CAPTURE;
    private int nextPublishIndex;
    private boolean journalWritten;
    private boolean cleanupStagingOnStop = true;
    @Nullable private RevCommit commit;

    public LvcSemanticCommitTask(LvcOperationHandle handle, Path repositoryDirectory, Level world,
                                 LvcPlayerIdentity player, String message, Mode mode,
                                 List<LvcManifest.Region> updatedRegions, LvcTaskCallbacks<Result> callbacks)
    {
        super(handle, mode.displayName, callbacks, true);
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        this.world = Objects.requireNonNull(world, "world");
        this.player = Objects.requireNonNull(player, "player");
        this.message = Objects.requireNonNull(message, "message");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.updatedRegions = List.copyOf(Objects.requireNonNull(updatedRegions, "updatedRegions"));
    }

    @Override
    public void init()
    {
        try
        {
            this.project = LvcSemanticTaskContext.readActiveProject(this.repositoryDirectory);
            LvcSemanticTaskContext.validatePlacementDimension(this.project.placement(), this.world);
            this.captureSite = this.mode == Mode.UPDATE_AREAS ? this.project.site().withRegions(this.updatedRegions) : this.project.site();
            this.backend = LvcWorldBackend.resolve(this.world);
            this.stagingStore = new LvcChunkStagingStore(
                    this.repositoryDirectory,
                    LvcOperationJournal.stagingDirectory(this.repositoryDirectory, this.handle())
            );
            LvcSiteWorkPlan plan = LvcSiteWorkPlan.create(this.captureSite, this.project.placement());
            this.captureSession = new LvcCaptureSession(
                    plan,
                    this.backend.createReader(this.world, this.backend == LvcWorldBackend.SERVUX),
                    this.stagingStore::writeObject,
                    false,
                    true
            );
            this.servuxRequests = this.backend == LvcWorldBackend.SERVUX ? LvcServuxBulkRequestPlanner.create(plan) : null;
            LvcOperationJournal.write(this.repositoryDirectory, this.mode.journalOperation, null, "capture");
            this.journalWritten = true;
            LvcDiagnostics.debug(this.handle(), "semantic commit initialized mode={} site={} dimension={} origin={} chunks={} updatedRegions={} backend={} lossy={} blockEntities={} entities={} servuxColumns={}",
                    this.mode, this.project.siteId(), this.project.placement().dimension(), this.project.placement().origin(),
                    this.captureSession.totalChunks(), this.updatedRegions.size(), this.backend.id(), this.backend.lossy(),
                    this.backend.capturesBlockEntities(), this.backend.capturesEntities(),
                    this.servuxRequests == null ? 0 : this.servuxRequests.totalColumns());
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
            LvcDiagnostics.debug(this.handle(), "semantic capture completed mode={} chunks={} objects={} canonicalBytes={} storedBytes={} blockEntityReadAttempts={} blockEntityRecords={} chunkFormat={} storageMode={} scheduledTicksStored=false",
                    this.mode, session.totalChunks(), this.captureResult.fullHashes().size(), session.fullHashContentBytes(), session.storedObjectBytes(),
                    session.blockEntityReadAttempts(), session.blockEntityRecords(),
                    LvcManifest.CHUNK_FORMAT, LvcChunkCodec.STORAGE_MODE);
            this.logLossyBlockEntityCapture(session);

            if (this.mode == Mode.SAVE_VERSION)
            {
                this.logSaveVersionMismatchSamples(this.captureResult);

                if (LvcSemanticRepository.saveVersionHasNoCommitChanges(
                        this.repositoryDirectory, this.captureSite(), this.captureResult.trackedHashes()))
                {
                    this.phase = Phase.DONE;
                    return true;
                }
            }

            this.publishObjectIds = new ArrayList<>(this.captureResult.fullHashes().values());
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
            this.commit = this.commitCapture();
            this.phase = Phase.DONE;
            return true;
        }

        return true;
    }

    @Override
    protected Result result() throws Exception
    {
        this.requireStagingStore().cleanup();
        LvcOperationJournal.delete(this.repositoryDirectory);
        this.cleanupStagingOnStop = false;
        return new Result(this.commit, this.captureSite().regions().size(), this.requireBackend().lossy());
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
            if (this.cleanupStagingOnStop && this.stagingStore != null)
            {
                try
                {
                    this.stagingStore.cleanup();

                    if (this.journalWritten)
                    {
                        LvcOperationJournal.delete(this.repositoryDirectory);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
    }

    @Nullable
    private RevCommit commitCapture() throws Exception
    {
        LvcSemanticTaskContext.ActiveProject currentProject = Objects.requireNonNull(this.project, "project");
        LvcCaptureEngine.Result currentCapture = Objects.requireNonNull(this.captureResult, "captureResult");

        if (this.mode == Mode.UPDATE_AREAS)
        {
            return LvcSemanticRepository.updateSiteAreasFromCapture(
                    this.repositoryDirectory,
                    currentProject.manifest(),
                    currentProject.siteId(),
                    this.updatedRegions,
                    currentCapture,
                    this.player,
                    this.message
            ).commit();
        }

        return LvcSemanticRepository.commitSiteFromCapture(
                this.repositoryDirectory,
                currentProject.manifest(),
                currentProject.siteId(),
                currentCapture,
                this.player,
                this.message
        ).commit();
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
        boolean ready = requests.ensureReadyForCurrentChunk(session, this.handle(), "semantic commit");
        this.waitingForServux = !ready;
        return ready;
    }

    private void logLossyBlockEntityCapture(LvcCaptureSession session) throws Exception
    {
        if (this.requireBackend().capturesBlockEntities())
        {
            return;
        }

        int previousBlockEntityRecords = LvcSemanticRepository.countBlockEntityRecords(
                this.repositoryDirectory, this.captureSite());

        if (previousBlockEntityRecords > 0)
        {
            LvcDiagnostics.info(this.handle(),
                    "semantic lossy capture omitted block entity payloads mode={} backend={} previousBlockEntityRecords={} capturedBlockEntityRecords={} readAttempts={}",
                    this.mode, this.requireBackend().id(), previousBlockEntityRecords,
                    session.blockEntityRecords(), session.blockEntityReadAttempts());
        }
        else
        {
            LvcDiagnostics.debug(this.handle(),
                    "semantic lossy capture had no previous block entity payloads mode={} backend={} readAttempts={}",
                    this.mode, this.requireBackend().id(), session.blockEntityReadAttempts());
        }
    }

    private LvcChunkStagingStore requireStagingStore()
    {
        return Objects.requireNonNull(this.stagingStore, "stagingStore");
    }

    private void logSaveVersionMismatchSamples(LvcCaptureEngine.Result capture) throws Exception
    {
        LvcSemanticTaskContext.ActiveProject currentProject = Objects.requireNonNull(this.project, "project");
        Map<String, String> expectedTrackedHashes = LvcSemanticRepository.computeTrackedHashesFromFullObjects(
                this.repositoryDirectory, this.captureSite());
        LvcSemanticScanResult scanResult = LvcSemanticScanResult.compare(
                currentProject.siteId(), expectedTrackedHashes, capture);

        if (scanResult.clean() || scanResult.unknownChunks() > 0)
        {
            return;
        }

        List<LvcSemanticScanMismatch> samples = LvcSemanticScanMismatchSampler.sample(
                this.repositoryDirectory,
                this.captureSite(),
                currentProject.placement(),
                this.world,
                expectedTrackedHashes,
                capture,
                MISMATCH_SAMPLE_LIMIT);

        LvcDiagnostics.info(this.handle(),
                "semantic save-version detected tracked discrepancies site={} changedChunks={} addedChunks={} removedChunks={} samples={}",
                scanResult.siteId(), scanResult.changedChunks(), scanResult.addedChunks(), scanResult.removedChunks(), samples.size());

        for (LvcSemanticScanMismatch sample : samples)
        {
            LvcDiagnostics.info(this.handle(), "semantic save-version mismatch sample: {}", sample.summary());
        }
    }

    private LvcManifest.Site captureSite()
    {
        return Objects.requireNonNull(this.captureSite, "captureSite");
    }

    public enum Mode
    {
        SAVE_VERSION("LVC Save Version", LvcOperationJournal.Operation.SAVE),
        UPDATE_AREAS("LVC Update Areas", LvcOperationJournal.Operation.UPDATE_AREAS);

        private final String displayName;
        private final LvcOperationJournal.Operation journalOperation;

        Mode(String displayName, LvcOperationJournal.Operation journalOperation)
        {
            this.displayName = displayName;
            this.journalOperation = journalOperation;
        }
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

    public record Result(@Nullable RevCommit commit, int regionCount, boolean lossyCapture)
    {
    }
}
