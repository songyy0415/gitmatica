package me.arnavpmr.lvc.overlay;

import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.git.LvcGitTreeReader;
import me.arnavpmr.lvc.git.LvcGitBranchOps;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.LvcUserActionException;
import me.arnavpmr.lvc.model.LvcManifest;
import me.arnavpmr.lvc.model.LvcSitePlacement;
import me.arnavpmr.lvc.capture.LvcMinecraftWorldReader;
import me.arnavpmr.lvc.storage.LvcRepository;
import me.arnavpmr.lvc.semantic.LvcSemanticSchematicBuilder;
import me.arnavpmr.lvc.project.LvcProjectPositions;
import me.arnavpmr.lvc.task.LvcRefreshMarker;
import me.arnavpmr.lvc.world.LvcWorldAccess;
import me.arnavpmr.lvc.integration.litematica.verifier.GitmaticaVerifierStartGuard;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.data.Color4f;

public final class LvcTrackingOverlayService
{
    private static final String TRACKING_OVERLAY_CACHE_DIRECTORY = LvcTrackingOverlayDescriptorStore.CACHE_DIRECTORY;
    private static final String TRACKING_OVERLAY_CACHE_FILE_STEM = "tracking-overlay";
    private static final String TRACKING_OVERLAY_CACHE_FILE_PREFIX = TRACKING_OVERLAY_CACHE_FILE_STEM + "-";
    private static final int TRACKING_OVERLAY_COMMIT_TOKEN_LENGTH = 8;
    private static final Color4f CHANGE_OVERLAY_ADDED = Color4f.fromColor(0x33CC33, 0.30f);
    private static final Color4f CHANGE_OVERLAY_REMOVED = Color4f.fromColor(0xFF3333, 0.30f);
    private static final Color4f CHANGE_OVERLAY_STATE = Color4f.fromColor(0xFAF000, 0.30f);
    private static final Color4f CHANGE_OVERLAY_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 0.30f);
    private static final Color4f CHANGE_MARKER_ADDED = Color4f.fromColor(0x33CC33, 1.0f);
    private static final Color4f CHANGE_MARKER_REMOVED = Color4f.fromColor(0xFF3333, 1.0f);
    private static final Color4f CHANGE_MARKER_STATE = Color4f.fromColor(0xFAF000, 1.0f);
    private static final Color4f CHANGE_MARKER_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 1.0f);
    @Nullable private static final Method SCHEMATIC_ENTITY_LOOKUP_REMOVE_BY_UUID = resolveSchematicEntityLookupRemoveByUuid();

    private LvcTrackingOverlayService()
    {
    }

    public static LvcTrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                                 @Nullable ClientLevel clientLevel,
                                                                 @Nullable ICompletionListener completionListener) throws IOException
    {
        return loadTrackingOverlay(repositoryDirectory, projectName, clientLevel, completionListener, true);
    }

    public static LvcTrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                                 @Nullable ClientLevel clientLevel,
                                                                 @Nullable ICompletionListener completionListener,
                                                                 boolean startVerifier) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(projectName, "projectName");

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        String siteId = LvcSemanticRepository.defaultSiteId(manifest);
        LvcManifest.Site site = manifest.site(siteId);
        LvcSitePlacement placementState = resolveSitePlacementForTrackingOverlay(repositoryDirectory, site);

        String overlayName = trackingOverlayDisplayName(repositoryDirectory, manifest.name());
        ServerLevel lootPreviewWorld = resolveLootPreviewWorld(clientLevel, placementState);
        LitematicaSchematic schematic = writeAndReloadSemanticTrackingSchematic(repositoryDirectory, manifest, siteId, placementState, overlayName, lootPreviewWorld);
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, overlayName, true, true);
        ClientLevel verifierWorld = clientLevel;

        if (clientLevel != null && !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            verifierWorld = null;
        }

        LvcTrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
        trackOverlay(repositoryDirectory, overlay, currentOverlayDescriptor(repositoryDirectory, siteId, placementState.dimension(),
                placement.getSchematicFile(), overlayName));
        return overlay;
    }

    public static void removeTrackingOverlay(Path repositoryDirectory) throws IOException
    {
        LvcRefreshMarker.write(repositoryDirectory, "overlay_reload", null);
        removeTrackingOverlay(repositoryDirectory, true);
    }

    public static void closeTrackingOverlay(Path repositoryDirectory)
    {
        removeTrackingOverlay(repositoryDirectory, false);
    }

    private static void removeTrackingOverlay(Path repositoryDirectory, boolean preserveOriginCache)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (preserveOriginCache)
        {
            rememberCurrentTrackingOverlayOrigin(repositoryDirectory);
        }
        else
        {
            LvcTrackingOverlayRegistry.removeOrigin(trackingOverlayKey(repositoryDirectory));
        }

        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.remove(trackingOverlayKey(repositoryDirectory));
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null)
        {
            try
            {
                LitematicaSchematic schematic = overlay.placement().getSchematic();
                purgeSchematicWorldEntitiesForTrackingPlacement(repositoryDirectory, overlay.placement());
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(overlay.placement(), true);
                SchematicHolder.getInstance().removeSchematic(schematic);
            }
            catch (RuntimeException | LinkageError e)
            {
                LvcDiagnostics.debug("LvcTrackingOverlayService: skipped active tracking overlay removal repo='{}' error='{}'",
                        repositoryDirectory, e.getMessage());
            }
        }

        try
        {
            removeSemanticTrackingCacheOverlay(repositoryDirectory);
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped placement-list tracking overlay removal repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
        }
    }

    @Nullable
    public static LvcTrackingOverlay getReusableSemanticTrackingOverlay(Path repositoryDirectory,
                                                                                       @Nullable ClientLevel clientLevel,
                                                                                       @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (hasTrackedGitChanges(repositoryDirectory))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused reusable overlay for dirty repository '{}'", repositoryDirectory);
            return null;
        }

        Path key = trackingOverlayKey(repositoryDirectory);
        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        String siteId = LvcSemanticRepository.defaultSiteId(manifest);
        LvcManifest.Site site = manifest.site(siteId);

        if (!isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return null;
        }

        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);

        if (cacheFile == null)
        {
            return null;
        }

        String expectedName = trackingOverlayDisplayName(repositoryDirectory, manifest.name());
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(key);
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null)
        {
            if (isMatchingTrackingPlacement(overlay.placement(), cacheFile, expectedName))
            {
                // Reusing an overlay must not change the user's current Litematica placement selection.
                attachCompletionListener(overlay, completionListener);
                LvcDiagnostics.debug("LvcTrackingOverlayService: reused active tracking overlay for '{}'", repositoryDirectory);
                return overlay;
            }

            LvcTrackingOverlayRegistry.remove(key);
        }

        SchematicPlacement restoredPlacement = findMatchingTrackingPlacement(cacheFile, expectedName);

        if (restoredPlacement == null)
        {
            return null;
        }

        LvcTrackingOverlay restoredOverlay = wrapExistingPlacement(restoredPlacement);
        trackOverlay(repositoryDirectory, restoredOverlay, currentOverlayDescriptor(repositoryDirectory, siteId, currentPlacementDimension(site),
                restoredPlacement.getSchematicFile(), expectedName));
        attachCompletionListener(restoredOverlay, completionListener);
        LvcDiagnostics.debug("LvcTrackingOverlayService: attached restart-persisted tracking overlay for '{}'", repositoryDirectory);
        return restoredOverlay;
    }

    public static boolean isCurrentTrackingOverlayLoaded(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return false;
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);

        if (cacheFile == null)
        {
            return false;
        }

        String expectedName = trackingOverlayDisplayName(repositoryDirectory, manifest.name());
        return findMatchingTrackingPlacement(cacheFile, expectedName) != null;
    }

    @Nullable
    public static LvcTrackingOverlay refreshVerifierIfCurrent(Path repositoryDirectory,
                                                                             @Nullable ClientLevel clientLevel,
                                                                             @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        OverlayTarget target = currentOverlayTarget(repositoryDirectory);

        if (target == null)
        {
            return null;
        }

        LvcTrackingOverlayEntry entry = activeEntryIfCurrent(repositoryDirectory, target);

        if (entry == null)
        {
            entry = persistedEntryIfCurrent(repositoryDirectory, target);
        }

        if (entry == null)
        {
            return null;
        }

        LvcTrackingOverlay overlay = restartVerifier(repositoryDirectory, entry.overlay(), target, clientLevel, completionListener);
        trackOverlay(repositoryDirectory, overlay, target.descriptor());
        LvcDiagnostics.debug("LvcTrackingOverlayService: refreshed verifier without rebuilding overlay repo='{}' commit='{}'",
                repositoryDirectory, target.commitId());
        return overlay;
    }

    public static String trackingOverlayDisplayName(Path repositoryDirectory, String projectName)
    {
        try
        {
            ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

            if (head != null)
            {
                String commitId = head.getName();
                return trackingOverlayDisplayNameForCommit(projectName, commitId);
            }
        }
        catch (Exception ignored)
        {
        }

        return projectName;
    }

    public static LitematicaSchematic writeAndReloadSemanticTrackingSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                                       String siteId, LvcSitePlacement placementState,
                                                                       String overlayName) throws IOException
    {
        return writeAndReloadSemanticTrackingSchematic(repositoryDirectory, manifest, siteId, placementState, overlayName, null);
    }

    public static LitematicaSchematic writeAndReloadSemanticTrackingSchematic(Path repositoryDirectory, LvcManifest manifest,
                                                                       String siteId, LvcSitePlacement placementState,
                                                                       String overlayName,
                                                                       @Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        Path cacheFile = writeSemanticTrackingCacheFile(repositoryDirectory, manifest, siteId, placementState, overlayName, lootPreviewWorld);
        removeTrackingOverlay(repositoryDirectory);
        LitematicaSchematic reloaded = reloadLitematicaSchematic(cacheFile);

        if (reloaded == null)
        {
            throw new IOException("Failed to reload LVC tracking schematic cache: " + cacheFile);
        }

        reloaded.getMetadata().setName(overlayName);
        return reloaded;
    }

    public static boolean isSemanticTrackingCacheCurrent(Path repositoryDirectory) throws IOException
    {
        LvcTrackingOverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);
        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);

        if (descriptor == null || cacheFile == null || !Files.isRegularFile(cacheFile))
        {
            return false;
        }

        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null || !head.getName().equals(descriptor.commitId()) ||
                LvcTrackingOverlayRevision.PARENT.serializedName().equals(descriptor.revision()))
        {
            return false;
        }

        long cacheTime = Files.getLastModifiedTime(cacheFile).toMillis();
        long manifestTime = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.MANIFEST)).toMillis();
        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);

        if (!trackingOverlayDisplayNameForCommit(manifest.name(), head.getName()).equals(descriptor.overlayName()))
        {
            return false;
        }

        long hashIndexTime = LvcSemanticRepository.hashIndexesLastModified(repositoryDirectory, manifest);
        return cacheTime >= manifestTime && cacheTime >= hashIndexTime;
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LitematicaSchematic schematic) throws IOException
    {
        Path cacheFile = newSemanticTrackingCacheFile(repositoryDirectory);
        Files.createDirectories(cacheFile.getParent());

        if (!schematic.writeToFile(cacheFile.getParent(), cacheFile.getFileName().toString(), false))
        {
            throw new IOException("Failed to write LVC tracking schematic cache: " + cacheFile);
        }

        return cacheFile;
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LvcManifest manifest,
                                               String siteId, LvcSitePlacement placementState,
                                               String overlayName) throws IOException
    {
        return writeSemanticTrackingCacheFile(repositoryDirectory, manifest, siteId, placementState, overlayName, null);
    }

    public static Path writeSemanticTrackingCacheFile(Path repositoryDirectory, LvcManifest manifest,
                                               String siteId, LvcSitePlacement placementState,
                                               String overlayName,
                                               @Nullable ServerLevel lootPreviewWorld) throws IOException
    {
        LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildWorkingTreeSchematic(repositoryDirectory, manifest, siteId, placementState, lootPreviewWorld);
        schematic.getMetadata().setName(overlayName);
        return writeSemanticTrackingCacheFile(repositoryDirectory, schematic);
    }

    @Nullable
    public static ServerLevel resolveLootPreviewWorld(@Nullable ClientLevel clientLevel,
                                                      LvcSitePlacement placementState)
    {
        Objects.requireNonNull(placementState, "placementState");

        if (clientLevel == null || !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            return null;
        }

        Level captureWorld = LvcWorldAccess.resolveSemanticCaptureWorld(clientLevel);
        return captureWorld instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    public static LitematicaSchematic reloadLitematicaSchematic(Path structureFile)
    {
        SchematicHolder holder = SchematicHolder.getInstance();

        for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
        {
            if (structureFile.equals(schematic.getFile()))
            {
                holder.removeSchematic(schematic);
            }
        }

        return holder.getOrLoad(structureFile);
    }

    public static LvcTrackingOverlay addSemanticTrackingOverlay(Path repositoryDirectory,
                                                                               LitematicaSchematic schematic,
                                                                               String siteId,
                                                                               LvcSitePlacement placementState,
                                                                               String overlayName,
                                                                               @Nullable ClientLevel clientLevel,
                                                                               @Nullable ICompletionListener completionListener,
                                                                               boolean startVerifier) throws IOException
    {
        return addSemanticTrackingOverlay(repositoryDirectory, schematic, siteId, placementState, overlayName,
                clientLevel, completionListener, startVerifier,
                currentOverlayDescriptor(repositoryDirectory, siteId, placementState.dimension(),
                        schematic.getFile(), overlayName));
    }

    public static LvcTrackingOverlay addSemanticTrackingOverlayForRevision(
            Path repositoryDirectory,
            LitematicaSchematic schematic,
            String siteId,
            LvcSitePlacement placementState,
            String overlayName,
            @Nullable ClientLevel clientLevel,
            @Nullable ICompletionListener completionListener,
            boolean startVerifier,
            LvcTrackingOverlayRevision revision,
            String descriptorCommitId) throws IOException
    {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(descriptorCommitId, "descriptorCommitId");
        LvcTrackingOverlayDescriptor descriptor = overlayDescriptor(descriptorCommitId, siteId, placementState.dimension(),
                schematic.getFile(), overlayName, revision);
        return addSemanticTrackingOverlay(repositoryDirectory, schematic, siteId, placementState, overlayName,
                clientLevel, completionListener, startVerifier, descriptor);
    }

    private static LvcTrackingOverlay addSemanticTrackingOverlay(
            Path repositoryDirectory,
            LitematicaSchematic schematic,
            String siteId,
            LvcSitePlacement placementState,
            String overlayName,
            @Nullable ClientLevel clientLevel,
            @Nullable ICompletionListener completionListener,
            boolean startVerifier,
            @Nullable LvcTrackingOverlayDescriptor descriptor) throws IOException
    {
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, overlayName, true, true);
        ClientLevel verifierWorld = clientLevel;

        if (clientLevel != null && !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            verifierWorld = null;
        }

        LvcTrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
        trackOverlay(repositoryDirectory, overlay, descriptor);
        return overlay;
    }

    public static Path semanticTrackingCachePath(Path repositoryDirectory)
    {
        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);
        return cacheFile != null ? cacheFile : defaultSemanticTrackingCacheFile(repositoryDirectory);
    }

    public static boolean isSemanticTrackingCachePath(@Nullable Path path)
    {
        if (path == null)
        {
            return false;
        }

        Path normalized = path.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        Path cacheDirectory = normalized.getParent();
        Path gitDirectory = cacheDirectory != null ? cacheDirectory.getParent() : null;
        Path cacheDirectoryName = cacheDirectory != null ? cacheDirectory.getFileName() : null;
        Path gitDirectoryName = gitDirectory != null ? gitDirectory.getFileName() : null;

        return fileName != null &&
                isGeneratedTrackingCacheFileName(fileName.toString()) &&
                cacheDirectoryName != null &&
                TRACKING_OVERLAY_CACHE_DIRECTORY.equals(cacheDirectoryName.toString()) &&
                gitDirectoryName != null &&
                ".git".equals(gitDirectoryName.toString());
    }

    public static boolean isSemanticTrackingPlacement(@Nullable SchematicPlacement placement)
    {
        return placement != null && isSemanticTrackingCachePath(placement.getSchematicFile());
    }

    public static LvcTrackingOverlayRevision trackingOverlayRevision(Path repositoryDirectory,
                                                                  SchematicPlacement placement)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(placement, "placement");
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(trackingOverlayKey(repositoryDirectory));
        LvcTrackingOverlayDescriptor descriptor = entry != null && entry.overlay().placement() == placement ?
                entry.descriptor() : readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null && descriptor.matchesPlacement(repositoryDirectory, placement) &&
                LvcTrackingOverlayRevision.PARENT.serializedName().equals(descriptor.revision()))
        {
            return LvcTrackingOverlayRevision.PARENT;
        }

        return LvcTrackingOverlayRevision.CURRENT;
    }

    @Nullable
    public static SchematicPlacement findTrackingPlacement(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try
        {
            SchematicPlacement placement = findTrackingPlacementUnsafe(repositoryDirectory);

            if (placement != null)
            {
                cacheTrackingOverlayOrigin(repositoryDirectory, placement.getOrigin());
            }

            return placement;
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcTrackingOverlayRegistry.remove(trackingOverlayKey(repositoryDirectory));
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped tracking overlay lookup repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return null;
        }
    }

    public static SchematicPlacement requireTrackingPlacement(Path repositoryDirectory) throws IOException
    {
        SchematicPlacement placement = findTrackingPlacement(repositoryDirectory);

        if (placement == null)
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_PLACEMENT,
                    "Load the Gitmatica project placement before using this operation");
        }

        return placement;
    }

    public static LvcSitePlacement requireSitePlacement(Path repositoryDirectory, LvcManifest.Site site) throws IOException
    {
        Objects.requireNonNull(site, "site");
        return sitePlacementFromPlacement(site, requireTrackingPlacement(repositoryDirectory));
    }

    public static LvcSitePlacement requireCurrentOrCachedSitePlacement(Path repositoryDirectory,
                                                                       LvcManifest.Site site) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");

        SchematicPlacement placement = findTrackingPlacement(repositoryDirectory);

        if (placement != null)
        {
            return sitePlacementFromPlacement(site, placement);
        }

        BlockPos cachedOrigin = LvcTrackingOverlayRegistry.origin(trackingOverlayKey(repositoryDirectory));

        if (cachedOrigin != null)
        {
            return sitePlacementFromOrigin(site, cachedOrigin);
        }

        throw new LvcUserActionException(LvcUserActionException.Reason.MISSING_PLACEMENT,
                "Load the Gitmatica project placement before using this operation");
    }

    public static void seedTrackingOverlayOrigin(Path repositoryDirectory, LvcSitePlacement placement)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(placement, "placement");
        cacheTrackingOverlayOrigin(repositoryDirectory, LvcProjectPositions.blockPosFromList(placement.origin()));
    }

    public static LvcSitePlacement resolveSitePlacementForTrackingOverlay(Path repositoryDirectory,
                                                                          LvcManifest.Site site) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(site, "site");

        SchematicPlacement placement = findTrackingPlacement(repositoryDirectory);

        if (placement != null)
        {
            return sitePlacementFromPlacement(site, placement);
        }

        BlockPos cachedOrigin = LvcTrackingOverlayRegistry.origin(trackingOverlayKey(repositoryDirectory));

        if (cachedOrigin != null)
        {
            return sitePlacementFromOrigin(site, cachedOrigin);
        }

        LvcSitePlacement placementAtPlayer = createSitePlacementAtCurrentPlayer(site);
        LvcTrackingOverlayRegistry.putOrigin(
                trackingOverlayKey(repositoryDirectory),
                LvcProjectPositions.blockPosFromList(placementAtPlayer.origin())
        );
        return placementAtPlayer;
    }

    public static LvcSitePlacement createSitePlacementAtCurrentPlayer(LvcManifest.Site site) throws IOException
    {
        Objects.requireNonNull(site, "site");
        return new LvcSitePlacement(currentPlacementDimension(site), blockPosToList(currentPlayerBlockPosForPlacement()));
    }

    @Nullable
    public static BlockPos trackingOverlayOrigin(Path repositoryDirectory)
    {
        SchematicPlacement placement = findTrackingPlacement(repositoryDirectory);
        return placement == null ? null : placement.getOrigin();
    }

    @Nullable
    public static Color4f semanticTrackingOverlayColor(OverlayType overlayType)
    {
        // LVC names changes from the current world relative to the tracked schematic baseline.
        return switch (overlayType)
        {
            case EXTRA -> CHANGE_OVERLAY_ADDED;
            case MISSING -> CHANGE_OVERLAY_REMOVED;
            case WRONG_STATE -> CHANGE_OVERLAY_STATE;
            case WRONG_BLOCK, DIFF_BLOCK -> CHANGE_OVERLAY_WRONG_BLOCK;
            default -> null;
        };
    }

    public static Color4f semanticTrackingMismatchColor(MismatchType mismatchType)
    {
        return switch (mismatchType)
        {
            case EXTRA -> CHANGE_MARKER_ADDED;
            case MISSING -> CHANGE_MARKER_REMOVED;
            case WRONG_STATE -> CHANGE_MARKER_STATE;
            case WRONG_BLOCK, DIFF_BLOCK -> CHANGE_MARKER_WRONG_BLOCK;
            default -> mismatchType.getColor();
        };
    }

    @Nullable
    public static Path semanticTrackingRepositoryDirectory(@Nullable Path path)
    {
        if (!isSemanticTrackingCachePath(path))
        {
            return null;
        }

        Path cacheDirectory = path.toAbsolutePath().normalize().getParent();
        Path gitDirectory = cacheDirectory != null ? cacheDirectory.getParent() : null;
        Path repositoryDirectory = gitDirectory != null ? gitDirectory.getParent() : null;
        return repositoryDirectory != null ? repositoryDirectory.normalize() : null;
    }

    private static boolean hasTrackedGitChanges(Path repositoryDirectory)
    {
        try
        {
            return LvcGitBranchOps.hasUncommittedChanges(repositoryDirectory);
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: treating overlay cache as non-reusable because dirty check failed repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return true;
        }
    }

    public static boolean focusTrackingOverlay(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Path key = trackingOverlayKey(repositoryDirectory);
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(key);
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null)
        {
            return false;
        }

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(overlay.placement()))
        {
            LvcTrackingOverlayRegistry.remove(key);
            return false;
        }

        focusTrackingOverlayInternal(repositoryDirectory, overlay.placement());
        return true;
    }

    public static boolean updateTrackingOverlayOrigin(Path repositoryDirectory, BlockPos origin)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(origin, "origin");

        try
        {
            return updateTrackingOverlayOriginUnsafe(repositoryDirectory, origin);
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcTrackingOverlayRegistry.remove(trackingOverlayKey(repositoryDirectory));
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped tracking overlay move repo='{}' origin='{}' error='{}'",
                    repositoryDirectory, origin, e.getMessage());
            return false;
        }
    }

    private static boolean updateTrackingOverlayOriginUnsafe(Path repositoryDirectory, BlockPos origin)
    {
        Path key = trackingOverlayKey(repositoryDirectory);
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(key);
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;
        SchematicPlacement placement = overlay != null ? overlay.placement() : null;

        if (placement == null || !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement))
        {
            LvcTrackingOverlayRegistry.remove(key);
            placement = findTrackingPlacementByRepository(repositoryDirectory);

            if (placement == null)
            {
                LvcDiagnostics.debug("LvcTrackingOverlayService: no active tracking overlay to move repo='{}' origin='{}'",
                        repositoryDirectory, origin);
                return false;
            }

            overlay = wrapExistingPlacement(placement);
            trackOverlay(repositoryDirectory, overlay, descriptorForExistingPlacement(repositoryDirectory, placement));
        }

        BlockPos oldOrigin = placement.getOrigin();

        if (!oldOrigin.equals(origin))
        {
            placement.setOrigin(origin, InfoUtils.INFO_MESSAGE_CONSUMER);
        }

        boolean updated = placement.getOrigin().equals(origin);

        if (updated && overlay != null)
        {
            trackOverlay(repositoryDirectory, overlay, descriptorForExistingPlacement(repositoryDirectory, placement));
        }

        LvcDiagnostics.debug("LvcTrackingOverlayService: moved tracking overlay origin repo='{}' placement='{}' old='{}' new='{}' updated={}",
                repositoryDirectory, placement.getName(), oldOrigin, origin, updated);
        return updated;
    }

    public static boolean focusTrackingOverlay(Path repositoryDirectory, SchematicPlacement placement)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(placement, "placement");

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused focus for unregistered tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
            return false;
        }

        Path expectedRepository = semanticTrackingRepositoryDirectory(placement.getSchematicFile());

        if (expectedRepository == null || !trackingOverlayKey(repositoryDirectory).equals(trackingOverlayKey(expectedRepository)))
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: refused focus for mismatched tracking overlay repo='{}' placement='{}' expectedRepo='{}'",
                    repositoryDirectory, placement.getName(), expectedRepository);
            return false;
        }

        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(trackingOverlayKey(repositoryDirectory));
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null || overlay.placement() != placement)
        {
            trackOverlay(repositoryDirectory, wrapExistingPlacement(placement), descriptorForExistingPlacement(repositoryDirectory, placement));
            LvcDiagnostics.debug("LvcTrackingOverlayService: attached placement-list tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
        }

        focusTrackingOverlayInternal(repositoryDirectory, placement);
        return true;
    }

    private static void trackOverlay(Path repositoryDirectory, LvcTrackingOverlay overlay,
                                     @Nullable LvcTrackingOverlayDescriptor descriptor)
    {
        cacheTrackingOverlayOrigin(repositoryDirectory, overlay.placement().getOrigin());
        LvcTrackingOverlayRegistry.put(trackingOverlayKey(repositoryDirectory), new LvcTrackingOverlayEntry(overlay, descriptor));

        if (descriptor != null)
        {
            List<Path> protectedCacheFiles = protectedSemanticTrackingCacheFiles(repositoryDirectory, overlay.placement().getSchematicFile());

            if (writeOverlayDescriptor(repositoryDirectory, descriptor))
            {
                removeOtherSemanticTrackingCacheOverlays(repositoryDirectory, overlay.placement());
                pruneSemanticTrackingCacheFiles(repositoryDirectory, protectedCacheFiles);
            }
        }
    }

    private static void cacheTrackingOverlayOrigin(Path repositoryDirectory, BlockPos origin)
    {
        LvcTrackingOverlayRegistry.putOrigin(trackingOverlayKey(repositoryDirectory), origin);
    }

    private static void rememberCurrentTrackingOverlayOrigin(Path repositoryDirectory)
    {
        try
        {
            SchematicPlacement placement = findTrackingPlacementUnsafe(repositoryDirectory);

            if (placement != null)
            {
                cacheTrackingOverlayOrigin(repositoryDirectory, placement.getOrigin());
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped tracking overlay origin remember repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
        }
    }

    private static Path trackingOverlayKey(Path repositoryDirectory)
    {
        return LvcTrackingOverlayRegistry.key(repositoryDirectory);
    }

    private static void removeSemanticTrackingCacheOverlay(Path repositoryDirectory)
    {
        for (SchematicPlacement placement : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            if (isTrackingCacheForRepository(repositoryDirectory, placement.getSchematicFile()))
            {
                purgeSchematicWorldEntitiesForTrackingPlacement(repositoryDirectory, placement);
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement, true);
            }
        }

        SchematicHolder holder = SchematicHolder.getInstance();

        for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
        {
            if (isTrackingCacheForRepository(repositoryDirectory, schematic.getFile()))
            {
                holder.removeSchematic(schematic);
            }
        }

    }

    private static void removeOtherSemanticTrackingCacheOverlays(Path repositoryDirectory, SchematicPlacement keepPlacement)
    {
        try
        {
            for (SchematicPlacement placement : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                if (placement != keepPlacement && isTrackingCacheForRepository(repositoryDirectory, placement.getSchematicFile()))
                {
                    purgeSchematicWorldEntitiesForTrackingPlacement(repositoryDirectory, placement);
                    DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement, true);
                }
            }

            LitematicaSchematic keepSchematic = keepPlacement.getSchematic();
            SchematicHolder holder = SchematicHolder.getInstance();

            for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
            {
                if (schematic != keepSchematic && isTrackingCacheForRepository(repositoryDirectory, schematic.getFile()))
                {
                    holder.removeSchematic(schematic);
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped stale tracking overlay cleanup repo='{}' placement='{}' error='{}'",
                    repositoryDirectory, keepPlacement.getName(), e.getMessage());
        }
    }

    private static void purgeSchematicWorldEntitiesForTrackingPlacement(Path repositoryDirectory, SchematicPlacement placement)
    {
        try
        {
            WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
            Method removeByUuid = SCHEMATIC_ENTITY_LOOKUP_REMOVE_BY_UUID;

            if (schematicWorld == null || removeByUuid == null || !SchematicEntityLookup.class.isInstance(schematicWorld.getEntities()))
            {
                return;
            }

            Set<ChunkPos> touchedChunks = placement.getTouchedChunks(RequiredEnabled.ANY);
            List<AABB> bounds = trackingPlacementEntityBounds(placement);

            if (touchedChunks.isEmpty() && bounds.isEmpty())
            {
                return;
            }

            int beforeCount = schematicWorld.getRegularEntityCount();

            for (ChunkPos chunk : touchedChunks)
            {
                schematicWorld.unloadEntitiesByChunk(chunk.x(), chunk.z());
            }

            List<UUID> entitiesToRemove = new ArrayList<>();

            if (!bounds.isEmpty())
            {
                for (Entity entity : schematicWorld.getEntities().getAll())
                {
                    if (isWithinAnyBounds(entity.getBoundingBox(), bounds))
                    {
                        entitiesToRemove.add(entity.getUUID());
                    }
                }
            }

            int removedByBounds = 0;

            for (UUID uuid : entitiesToRemove)
            {
                Object removed = removeByUuid.invoke(schematicWorld.getEntities(), uuid, schematicWorld);

                if (Boolean.TRUE.equals(removed))
                {
                    removedByBounds++;
                }
            }

            int removedCount = beforeCount - schematicWorld.getRegularEntityCount();

            if (removedCount > 0)
            {
                LitematicaRenderer.getInstance().getWorldRenderer().clearWorldRenderStates();
                LitematicaRenderer.getInstance().getWorldRenderer().markNeedsUpdate();
                LvcDiagnostics.debug("LvcTrackingOverlayService: purged schematic entities for tracking overlay repo='{}' placement='{}' chunks={} removed={} removedByBounds={}",
                        repositoryDirectory, placement.getName(), touchedChunks.size(), removedCount, removedByBounds);
            }

        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped tracking overlay entity purge repo='{}' placement='{}' error='{}'",
                    repositoryDirectory, placement.getName(), e.getMessage());
        }
    }

    private static List<AABB> trackingPlacementEntityBounds(SchematicPlacement placement)
    {
        List<AABB> bounds = new ArrayList<>();

        for (Box box : placement.getSubRegionBoxes(RequiredEnabled.ANY).values())
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 != null && pos2 != null)
            {
                bounds.add(PositionUtils.createEnclosingAABB(pos1, pos2));
            }
        }

        return bounds;
    }

    private static boolean isWithinAnyBounds(AABB entityBounds, List<AABB> bounds)
    {
        for (AABB bound : bounds)
        {
            if (bound.intersects(entityBounds))
            {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private static Method resolveSchematicEntityLookupRemoveByUuid()
    {
        try
        {
            Method method = SchematicEntityLookup.class.getDeclaredMethod("remove", UUID.class, WorldSchematic.class);
            method.setAccessible(true);
            return method;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to resolve schematic entity removal error='{}'",
                    e.getMessage());
            return null;
        }
    }

    private static boolean isTrackingCacheForRepository(Path repositoryDirectory, @Nullable Path cacheFile)
    {
        Path expectedRepository = semanticTrackingRepositoryDirectory(cacheFile);
        return expectedRepository != null && trackingOverlayKey(repositoryDirectory).equals(trackingOverlayKey(expectedRepository));
    }

    private static boolean pathsEqual(Path expected, @Nullable Path actual)
    {
        return actual != null && expected.equals(actual.toAbsolutePath().normalize());
    }

    @Nullable
    private static SchematicPlacement findTrackingPlacementUnsafe(Path repositoryDirectory)
    {
        Path key = trackingOverlayKey(repositoryDirectory);
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(key);
        LvcTrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null &&
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(overlay.placement()))
        {
            return overlay.placement();
        }

        LvcTrackingOverlayRegistry.remove(key);
        return findTrackingPlacementByRepository(repositoryDirectory);
    }

    @Nullable
    private static SchematicPlacement findTrackingPlacementByCacheFile(Path cacheFile)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (pathsEqual(cacheFile, placement.getSchematicFile()))
            {
                return placement;
            }
        }

        return null;
    }

    @Nullable
    private static SchematicPlacement findTrackingPlacementByRepository(Path repositoryDirectory)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (isTrackingCacheForRepository(repositoryDirectory, placement.getSchematicFile()))
            {
                return placement;
            }
        }

        return null;
    }

    @Nullable
    private static SchematicPlacement findMatchingTrackingPlacement(Path cacheFile, String expectedName)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (isMatchingTrackingPlacement(placement, cacheFile, expectedName))
            {
                return placement;
            }
        }

        return null;
    }

    private static boolean isMatchingTrackingPlacement(SchematicPlacement placement, Path cacheFile,
                                                       String expectedName)
    {
        return expectedName.equals(placement.getName()) &&
                pathsEqual(cacheFile, placement.getSchematicFile()) &&
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement);
    }

    private static LvcTrackingOverlay wrapExistingPlacement(SchematicPlacement placement)
    {
        boolean hadVerifier = placement.hasVerifier();
        SchematicVerifier verifier = placement.getSchematicVerifier();
        boolean verifierStarted = hadVerifier && (verifier.isActive() || verifier.isPaused() || verifier.isFinished());
        return new LvcTrackingOverlay(placement, verifier, verifierStarted);
    }

    private static void attachCompletionListener(LvcTrackingOverlay overlay,
                                                 @Nullable ICompletionListener completionListener)
    {
        if (completionListener != null && overlay.verifierStarted() && !overlay.verifier().isFinished())
        {
            overlay.verifier().setCompletionListener(completionListener);
        }
    }

    private static void focusTrackingOverlayInternal(Path repositoryDirectory, SchematicPlacement placement)
    {
        if (DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement() != placement)
        {
            DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
            LvcDiagnostics.debug("LvcTrackingOverlayService: focused tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
        }
    }

    @Nullable
    private static LvcTrackingOverlayEntry activeEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
    {
        LvcTrackingOverlayEntry entry = LvcTrackingOverlayRegistry.entry(trackingOverlayKey(repositoryDirectory));

        if (entry == null)
        {
            return null;
        }

        if (entryMatchesTarget(entry, target))
        {
            return entry;
        }

        LvcTrackingOverlayRegistry.remove(trackingOverlayKey(repositoryDirectory));
        return null;
    }

    @Nullable
    private static LvcTrackingOverlayEntry persistedEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
    {
        if (!Files.isRegularFile(target.cacheFile()))
        {
            return null;
        }

        SchematicPlacement placement = findMatchingTrackingPlacement(target.cacheFile(), target.overlayName());

        if (placement == null)
        {
            return null;
        }

        LvcTrackingOverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null && !descriptorMatchesTarget(descriptor, target))
        {
            return null;
        }

        if (descriptor == null && !isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return null;
        }

        return new LvcTrackingOverlayEntry(wrapExistingPlacement(placement), descriptor != null ? descriptor : target.descriptor());
    }

    private static boolean entryMatchesTarget(LvcTrackingOverlayEntry entry, OverlayTarget target) throws IOException
    {
        if (!isMatchingTrackingPlacement(entry.overlay().placement(), target.cacheFile(), target.overlayName()))
        {
            return false;
        }

        if (entry.descriptor() != null)
        {
            return descriptorMatchesTarget(entry.descriptor(), target);
        }

        return isSemanticTrackingCacheCurrent(target.repositoryDirectory());
    }

    private static LvcTrackingOverlay restartVerifier(Path repositoryDirectory,
                                                                     LvcTrackingOverlay overlay,
                                                                     OverlayTarget target,
                                                                     @Nullable ClientLevel clientLevel,
                                                                     @Nullable ICompletionListener completionListener)
    {
        focusTrackingOverlayInternal(repositoryDirectory, overlay.placement());

        SchematicVerifier verifier = overlay.placement().getSchematicVerifier();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        boolean verifierStarted = false;

        if (clientLevel != null && schematicWorld != null && LvcMinecraftWorldReader.dimensionId(clientLevel).equals(target.dimension()))
        {
            GitmaticaVerifierStartGuard.runDirectly(() -> verifier.startVerification(clientLevel, schematicWorld, overlay.placement(), completionListener));
            verifierStarted = true;
        }
        else
        {
            verifier.reset();
            LvcDiagnostics.debug("LvcTrackingOverlayService: reused tracking overlay '{}' without starting verifier", overlay.placement().getName());
        }

        return new LvcTrackingOverlay(overlay.placement(), verifier, verifierStarted);
    }

    @Nullable
    private static OverlayTarget currentOverlayTarget(Path repositoryDirectory) throws IOException
    {
        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null)
        {
            return null;
        }

        String commitId = head.getName();
        LvcManifest manifest = readCommitManifest(repositoryDirectory, commitId);
        String siteId = LvcSemanticRepository.defaultSiteId(manifest);
        LvcManifest.Site site = manifest.site(siteId);
        String overlayName = trackingOverlayDisplayNameForCommit(manifest.name(), commitId);
        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);

        if (cacheFile == null)
        {
            return null;
        }

        SchematicPlacement placement = findMatchingTrackingPlacement(cacheFile, overlayName);

        if (placement == null)
        {
            return null;
        }

        String dimension = currentPlacementDimension(site);
        LvcTrackingOverlayDescriptor descriptor = currentOverlayDescriptor(repositoryDirectory, siteId, dimension, cacheFile, overlayName);
        return new OverlayTarget(repositoryDirectory.toAbsolutePath().normalize(), commitId, siteId, dimension,
                cacheFile, overlayName, descriptor);
    }

    private static LvcManifest readCommitManifest(Path repositoryDirectory, String commitId) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit commit = LvcGitTreeReader.resolveCommit(repository, revWalk, commitId);
            return LvcSemanticRepository.readCommitManifest(repository, commit);
        }
    }

    private static String trackingOverlayDisplayNameForCommit(String projectName, String commitId)
    {
        return projectName + " @ " + shortCommitToken(commitId);
    }

    private static BlockPos currentPlayerBlockPosForPlacement() throws IOException
    {
        if (Minecraft.getInstance().player == null)
        {
            throw new IOException("Open the Gitmatica project while a player is loaded to place it at the current position");
        }

        return fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(Minecraft.getInstance().player);
    }

    @Nullable
    private static LvcTrackingOverlayDescriptor currentOverlayDescriptor(Path repositoryDirectory, String siteId,
                                                             String dimension,
                                                             @Nullable Path cacheFile,
                                                             String overlayName) throws IOException
    {
        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null || cacheFile == null)
        {
            return null;
        }

        return overlayDescriptor(head.getName(), siteId, dimension, cacheFile, overlayName,
                LvcTrackingOverlayRevision.CURRENT);
    }

    @Nullable
    private static LvcTrackingOverlayDescriptor overlayDescriptor(String commitId, String siteId, String dimension,
                                                       @Nullable Path cacheFile, String overlayName,
                                                       LvcTrackingOverlayRevision revision)
    {
        if (cacheFile == null)
        {
            return null;
        }

        return new LvcTrackingOverlayDescriptor(commitId, siteId, dimension,
                cacheFile.toAbsolutePath().normalize().toString(), overlayName, revision.serializedName());
    }

    @Nullable
    private static LvcTrackingOverlayDescriptor descriptorForExistingPlacement(Path repositoryDirectory, SchematicPlacement placement)
    {
        try
        {
            LvcTrackingOverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

            if (descriptor != null && descriptor.matchesPlacement(repositoryDirectory, placement))
            {
                return descriptor;
            }

            OverlayTarget target = currentOverlayTarget(repositoryDirectory);

            if (target != null && isMatchingTrackingPlacement(placement, target.cacheFile(), target.overlayName()))
            {
                return target.descriptor();
            }
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to describe existing overlay repo='{}' placement='{}' error='{}'",
                    repositoryDirectory, placement.getName(), e.getMessage());
        }

        return null;
    }

    private static boolean writeOverlayDescriptor(Path repositoryDirectory, LvcTrackingOverlayDescriptor descriptor)
    {
        return LvcTrackingOverlayDescriptorStore.write(repositoryDirectory, descriptor);
    }

    @Nullable
    private static LvcTrackingOverlayDescriptor readOverlayDescriptor(Path repositoryDirectory)
    {
        return LvcTrackingOverlayDescriptorStore.read(repositoryDirectory);
    }

    @Nullable
    private static Path currentSemanticTrackingCacheFile(Path repositoryDirectory)
    {
        LvcTrackingOverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

        if (descriptor == null || descriptor.cacheFile() == null)
        {
            return null;
        }

        Path cacheFile = Path.of(descriptor.cacheFile()).toAbsolutePath().normalize();
        return isTrackingCacheForRepository(repositoryDirectory, cacheFile) &&
                isCurrentTrackingCacheFileName(repositoryDirectory, cacheFile) ? cacheFile : null;
    }

    private static Path newSemanticTrackingCacheFile(Path repositoryDirectory)
    {
        return semanticTrackingCacheDirectory(repositoryDirectory)
                .resolve(TRACKING_OVERLAY_CACHE_FILE_PREFIX + cacheFileToken(repositoryDirectory) + "-" + UUID.randomUUID() + LitematicaSchematic.FILE_EXTENSION)
                .normalize();
    }

    private static Path defaultSemanticTrackingCacheFile(Path repositoryDirectory)
    {
        return semanticTrackingCacheDirectory(repositoryDirectory)
                .resolve(TRACKING_OVERLAY_CACHE_FILE_STEM + LitematicaSchematic.FILE_EXTENSION)
                .normalize();
    }

    private static Path semanticTrackingCacheDirectory(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(TRACKING_OVERLAY_CACHE_DIRECTORY)
                .normalize();
    }

    private static String cacheFileToken(Path repositoryDirectory)
    {
        try
        {
            ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

            if (head != null)
            {
                String commitId = head.getName();
                return shortCommitToken(commitId);
            }
        }
        catch (Exception ignored)
        {
        }

        return "unborn";
    }

    private static boolean isCurrentTrackingCacheFileName(Path repositoryDirectory, Path cacheFile)
    {
        Path fileName = cacheFile.getFileName();
        return fileName != null &&
                fileName.toString().startsWith(TRACKING_OVERLAY_CACHE_FILE_PREFIX + cacheFileToken(repositoryDirectory) + "-") &&
                fileName.toString().endsWith(LitematicaSchematic.FILE_EXTENSION);
    }

    private static String shortCommitToken(String commitId)
    {
        return commitId.substring(0, Math.min(TRACKING_OVERLAY_COMMIT_TOKEN_LENGTH, commitId.length()));
    }

    private static boolean isGeneratedTrackingCacheFileName(String fileName)
    {
        return fileName.endsWith(LitematicaSchematic.FILE_EXTENSION) &&
                (fileName.equals(TRACKING_OVERLAY_CACHE_FILE_STEM + LitematicaSchematic.FILE_EXTENSION) ||
                        fileName.startsWith(TRACKING_OVERLAY_CACHE_FILE_PREFIX));
    }

    private static List<Path> protectedSemanticTrackingCacheFiles(Path repositoryDirectory, @Nullable Path keepFile)
    {
        List<Path> cacheFiles = new ArrayList<>();
        addProtectedCacheFile(repositoryDirectory, cacheFiles, keepFile);

        LvcTrackingOverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null)
        {
            addProtectedCacheFile(repositoryDirectory, cacheFiles, descriptor.cacheFilePath());
        }

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            addProtectedCacheFile(repositoryDirectory, cacheFiles, placement.getSchematicFile());
        }

        for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics())
        {
            addProtectedCacheFile(repositoryDirectory, cacheFiles, schematic.getFile());
        }

        return cacheFiles;
    }

    private static void addProtectedCacheFile(Path repositoryDirectory, List<Path> cacheFiles, @Nullable Path cacheFile)
    {
        if (cacheFile == null || !isTrackingCacheForRepository(repositoryDirectory, cacheFile))
        {
            return;
        }

        Path normalized = cacheFile.toAbsolutePath().normalize();

        if (!containsPath(cacheFiles, normalized))
        {
            cacheFiles.add(normalized);
        }
    }

    private static void pruneSemanticTrackingCacheFiles(Path repositoryDirectory, List<Path> protectedCacheFiles)
    {
        Path cacheDirectory = semanticTrackingCacheDirectory(repositoryDirectory);

        if (!Files.isDirectory(cacheDirectory))
        {
            return;
        }

        try (var stream = Files.newDirectoryStream(cacheDirectory))
        {
            for (Path cacheFile : stream)
            {
                Path fileName = cacheFile.getFileName();

                if (fileName == null || !isGeneratedTrackingCacheFileName(fileName.toString()) || containsPath(protectedCacheFiles, cacheFile))
                {
                    continue;
                }

                Files.deleteIfExists(cacheFile);
            }
        }
        catch (IOException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to prune stale overlay cache files repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
        }
    }

    private static boolean containsPath(List<Path> paths, Path path)
    {
        for (Path candidate : paths)
        {
            if (pathsEqual(candidate, path))
            {
                return true;
            }
        }

        return false;
    }

    private static LvcSitePlacement sitePlacementFromPlacement(LvcManifest.Site site, SchematicPlacement placement)
    {
        return sitePlacementFromOrigin(site, placement.getOrigin());
    }

    private static LvcSitePlacement sitePlacementFromOrigin(LvcManifest.Site site, BlockPos origin)
    {
        return new LvcSitePlacement(currentPlacementDimension(site), blockPosToList(origin));
    }

    private static String currentPlacementDimension(LvcManifest.Site site)
    {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft != null ? minecraft.level : null;
        return level != null ? LvcMinecraftWorldReader.dimensionId(level) : site.dimension();
    }

    private static List<Integer> blockPosToList(BlockPos pos)
    {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static LvcTrackingOverlay addTrackingOverlay(Path repositoryDirectory,
                                                                        SchematicPlacement placement,
                                                                        @Nullable ClientLevel clientLevel,
                                                                        @Nullable ICompletionListener completionListener,
                                                                        boolean startVerifier)
    {
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        focusTrackingOverlayInternal(repositoryDirectory, placement);

        SchematicVerifier verifier = placement.getSchematicVerifier();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        boolean verifierStarted = false;

        if (startVerifier && clientLevel != null && schematicWorld != null)
        {
            GitmaticaVerifierStartGuard.runDirectly(() -> verifier.startVerification(clientLevel, schematicWorld, placement, completionListener));
            verifierStarted = true;
        }

        LvcDiagnostics.debug("LvcTrackingOverlayService: added tracking overlay repo='{}' placement='{}' startVerifier={} verifierStarted={} colorPalette='added=green removed=red changed_state=yellow wrong_block=orange'",
                repositoryDirectory, placement.getName(), startVerifier, verifierStarted);

        return new LvcTrackingOverlay(placement, verifier, verifierStarted);
    }

    private record OverlayTarget(Path repositoryDirectory, String commitId, String siteId, String dimension,
                                 Path cacheFile, String overlayName, LvcTrackingOverlayDescriptor descriptor)
    {
    }

    private static boolean descriptorMatchesTarget(LvcTrackingOverlayDescriptor descriptor, OverlayTarget target)
    {
        return descriptor.matches(
                target.commitId(),
                target.siteId(),
                target.dimension(),
                target.cacheFile(),
                target.overlayName()
        );
    }
}
