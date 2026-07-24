package me.niicide.lvc.gui;

import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifiers;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierStartGuard;
import me.niicide.lvc.semantic.LvcSemanticScanResult;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcFriendlyErrors.Operation;
import me.niicide.lvc.task.LvcAuthoritativeScanSync;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcSemanticScanTask;
import me.niicide.lvc.task.LvcTaskCallbacks;
import me.niicide.lvc.task.LvcTaskRegistry;
import me.niicide.lvc.task.LvcTaskScheduling;
import me.niicide.lvc.world.LvcWorldAccess;
import me.niicide.lvc.world.LvcWorldBackend;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class LvcVerifierStartWorkflow
{
    private LvcVerifierStartWorkflow()
    {
    }

    public static boolean startIfGitmatica(SchematicPlacement placement,
                                           ICompletionListener verifierCompletionListener,
                                           Runnable refreshGui)
    {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(refreshGui, "refreshGui");

        Path repositoryDirectory = LvcTrackingOverlayService.semanticTrackingRepositoryDirectory(placement.getSchematicFile());

        if (repositoryDirectory == null)
        {
            return false;
        }

        SchematicVerifier verifier = placement.getSchematicVerifier();

        if (verifier.isPaused())
        {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientWorld = minecraft.level;

        if (clientWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return true;
        }

        if (SchematicWorldHandler.getSchematicWorld() == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
            return true;
        }

        LvcWorldBackend backend = LvcWorldBackend.resolve(clientWorld);

        if (backend != LvcWorldBackend.DIRECT)
        {
            return startRemoteVerifierAfterScan(repositoryDirectory, placement, verifierCompletionListener, refreshGui,
                    clientWorld, verifier, backend);
        }

        ServerLevel serverWorld;

        try
        {
            Level authoritativeWorld = LvcWorldAccess.resolveSemanticRestoreWorld(clientWorld);

            if (!(authoritativeWorld instanceof ServerLevel resolvedServerWorld))
            {
                throw new IllegalStateException("Start Verification requires a server-authoritative world");
            }

            serverWorld = resolvedServerWorld;
        }
        catch (Exception e)
        {
            LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "gitmatica.error.lvc_project.start_verification_failed", e);
            return true;
        }

        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Start Verification", repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return true;
        }

        try
        {
            LvcDiagnostics.info(handle.get(), "Gitmatica verifier start delayed until three-way scan/client sync completes repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
            LvcAuthoritativeScanSync.schedule(
                    handle.get(),
                    repositoryDirectory,
                    serverWorld,
                    clientWorld,
                    LvcTaskCallbacks.of(
                            result -> startVerifierAfterSync(placement, verifierCompletionListener, refreshGui, result),
                            e -> LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "gitmatica.error.lvc_project.start_verification_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_aborted", "LVC Start Verification")
                    )
            );
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "gitmatica.error.lvc_project.start_verification_failed", e);
            return true;
        }

        int staleErrors = verifier.getTotalErrors();
        int staleInventoryErrors = GitmaticaVerifiers.extension(verifier).gitmatica$getWrongInventories();
        verifier.reset();
        refreshGui.run();
        LvcDiagnostics.debug(handle.get(), "Gitmatica verifier cleared stale rows before preflight repo='{}' placement='{}' errors={} inventoryErrors={}",
                repositoryDirectory, placement.getName(), staleErrors, staleInventoryErrors);
        LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_started", "LVC Start Verification");

        return true;
    }

    private static boolean startRemoteVerifierAfterScan(Path repositoryDirectory,
                                                        SchematicPlacement placement,
                                                        ICompletionListener verifierCompletionListener,
                                                        Runnable refreshGui,
                                                        ClientLevel clientWorld,
                                                        SchematicVerifier verifier,
                                                        LvcWorldBackend backend)
    {
        Optional<LvcOperationHandle> handle = LvcTaskRegistry.tryAcquire("LVC Start Verification", repositoryDirectory);

        if (handle.isEmpty())
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.operation_running",
                    LvcTaskRegistry.activeOperationName());
            return true;
        }

        try
        {
            LvcDiagnostics.info(handle.get(), "Gitmatica remote verifier start delayed until backend scan completes repo='{}' placement='{}' backend={}",
                    repositoryDirectory, placement.getName(), backend.id());
            LvcSemanticScanTask task = new LvcSemanticScanTask(
                    handle.get(),
                    repositoryDirectory,
                    clientWorld,
                    LvcTaskCallbacks.of(
                            result -> startVerifierAfterRemoteScan(placement, verifierCompletionListener, refreshGui, result),
                            e -> LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "gitmatica.error.lvc_project.start_verification_failed", e),
                            () -> LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_aborted", "LVC Start Verification")
                    ),
                    true
            );
            LvcTaskScheduling.scheduleClient(task);
        }
        catch (Exception e)
        {
            LvcTaskRegistry.release(handle.get());
            LvcGuiMessages.showTaskError(Operation.START_VERIFICATION, "gitmatica.error.lvc_project.start_verification_failed", e);
            return true;
        }

        int staleErrors = verifier.getTotalErrors();
        int staleInventoryErrors = GitmaticaVerifiers.extension(verifier).gitmatica$getWrongInventories();
        verifier.reset();
        refreshGui.run();
        LvcDiagnostics.debug(handle.get(), "Gitmatica remote verifier cleared stale rows before backend scan repo='{}' placement='{}' errors={} inventoryErrors={}",
                repositoryDirectory, placement.getName(), staleErrors, staleInventoryErrors);
        LvcGuiMessages.show(MessageType.INFO, "gitmatica.message.lvc_project.task_started", "LVC Start Verification");
        return true;
    }

    private static void startVerifierAfterSync(SchematicPlacement placement,
                                               ICompletionListener verifierCompletionListener,
                                               Runnable refreshGui,
                                               LvcAuthoritativeScanSync.Result result)
    {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientWorld = minecraft.level;
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (clientWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return;
        }

        if (schematicWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
            return;
        }

        LvcDiagnostics.info("Gitmatica verifier starting after three-way scan sync placement='{}' clean={} dirtyChunks={} staleChunks={} syncedPositions={}",
                placement.getName(), result.scanResult().clean(), result.scanResult().dirtyChunks(),
                result.staleChunks(), result.syncedPositions());
        GitmaticaVerifierStartGuard.runDirectly(() -> placement.getSchematicVerifier()
                .startVerification(clientWorld, schematicWorld, placement, verifierCompletionListener));
        refreshGui.run();
    }

    private static void startVerifierAfterRemoteScan(SchematicPlacement placement,
                                                     ICompletionListener verifierCompletionListener,
                                                     Runnable refreshGui,
                                                     me.niicide.lvc.semantic.LvcSemanticScanResult result)
    {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientWorld = minecraft.level;
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (clientWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "gitmatica.error.lvc_project.no_world");
            return;
        }

        if (schematicWorld == null)
        {
            LvcGuiMessages.show(MessageType.ERROR, "litematica.error.generic.schematic_world_not_loaded");
            return;
        }

        LvcDiagnostics.info("Gitmatica verifier starting after remote backend scan placement='{}' clean={} dirtyChunks={} unknownChunks={}",
                placement.getName(), result.clean(), result.dirtyChunks(), result.unknownChunks());
        GitmaticaVerifierStartGuard.runDirectly(() -> placement.getSchematicVerifier()
                .startVerification(clientWorld, schematicWorld, placement, verifierCompletionListener));
        refreshGui.run();
    }
}
