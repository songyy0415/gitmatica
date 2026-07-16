package me.niicide.lvc.overlay;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcUserActionException;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.project.LvcProjectPositions;
import me.niicide.lvc.task.LvcRefreshMarker;
import me.niicide.lvc.world.LvcWorldAccess;

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
    private static final String TRACKING_OVERLAY_CACHE_DIRECTORY = "lvc-cache";
    private static final String TRACKING_OVERLAY_CACHE_FILE_STEM = "tracking-overlay";
    private static final String TRACKING_OVERLAY_CACHE_FILE_PREFIX = TRACKING_OVERLAY_CACHE_FILE_STEM + "-";
    private static final String TRACKING_OVERLAY_DESCRIPTOR_FILE = "tracking-overlay.json";
    private static final int TRACKING_OVERLAY_COMMIT_TOKEN_LENGTH = 8;
    private static final Color4f CHANGE_OVERLAY_ADDED = Color4f.fromColor(0x33CC33, 0.30f);
    private static final Color4f CHANGE_OVERLAY_REMOVED = Color4f.fromColor(0xFF3333, 0.30f);
    private static final Color4f CHANGE_OVERLAY_STATE = Color4f.fromColor(0xFAF000, 0.30f);
    private static final Color4f CHANGE_OVERLAY_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 0.30f);
    private static final Color4f CHANGE_MARKER_ADDED = Color4f.fromColor(0x33CC33, 1.0f);
    private static final Color4f CHANGE_MARKER_REMOVED = Color4f.fromColor(0xFF3333, 1.0f);
    private static final Color4f CHANGE_MARKER_STATE = Color4f.fromColor(0xFAF000, 1.0f);
    private static final Color4f CHANGE_MARKER_WRONG_BLOCK = Color4f.fromColor(0xFF9010, 1.0f);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path, TrackingOverlayEntry> ACTIVE_TRACKING_OVERLAYS = new HashMap<>();
    private static final Map<Path, BlockPos> TRACKING_OVERLAY_ORIGIN_CACHE = new HashMap<>();
    @Nullable private static final Method SCHEMATIC_ENTITY_LOOKUP_REMOVE_BY_UUID = resolveSchematicEntityLookupRemoveByUuid();

    private LvcTrackingOverlayService()
    {
    }

    public enum TrackingOverlayRevision
    {
        CURRENT("current"),
        PARENT("parent");

        private final String serializedName;

        TrackingOverlayRevision(String serializedName)
        {
            this.serializedName = serializedName;
        }
    }

    public record TrackingOverlayRevisionTarget(String headCommitId, @Nullable String sourceCommitId,
                                                boolean airSchematic)
    {
    }

    public static LvcProjectService.TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
                                                                 @Nullable ClientLevel clientLevel,
                                                                 @Nullable ICompletionListener completionListener) throws IOException
    {
        return loadTrackingOverlay(repositoryDirectory, projectName, clientLevel, completionListener, true);
    }

    public static LvcProjectService.TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName,
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

        LvcProjectService.TrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
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
            TRACKING_OVERLAY_ORIGIN_CACHE.remove(trackingOverlayKey(repositoryDirectory));
        }

        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

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
    public static LvcProjectService.TrackingOverlay getReusableSemanticTrackingOverlay(Path repositoryDirectory,
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
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null)
        {
            if (isMatchingTrackingPlacement(overlay.placement(), cacheFile, expectedName))
            {
                // Reusing an overlay must not change the user's current Litematica placement selection.
                attachCompletionListener(overlay, completionListener);
                LvcDiagnostics.debug("LvcTrackingOverlayService: reused active tracking overlay for '{}'", repositoryDirectory);
                return overlay;
            }

            ACTIVE_TRACKING_OVERLAYS.remove(key);
        }

        SchematicPlacement restoredPlacement = findMatchingTrackingPlacement(cacheFile, expectedName);

        if (restoredPlacement == null)
        {
            return null;
        }

        LvcProjectService.TrackingOverlay restoredOverlay = wrapExistingPlacement(restoredPlacement);
        trackOverlay(repositoryDirectory, restoredOverlay, currentOverlayDescriptor(repositoryDirectory, siteId, currentPlacementDimension(site),
                restoredPlacement.getSchematicFile(), expectedName));
        attachCompletionListener(restoredOverlay, completionListener);
        LvcDiagnostics.debug("LvcTrackingOverlayService: attached restart-persisted tracking overlay for '{}'", repositoryDirectory);
        return restoredOverlay;
    }

    @Nullable
    public static LvcProjectService.TrackingOverlay refreshVerifierIfCurrent(Path repositoryDirectory,
                                                                             @Nullable ClientLevel clientLevel,
                                                                             @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        OverlayTarget target = currentOverlayTarget(repositoryDirectory);

        if (target == null)
        {
            return null;
        }

        TrackingOverlayEntry entry = activeEntryIfCurrent(repositoryDirectory, target);

        if (entry == null)
        {
            entry = persistedEntryIfCurrent(repositoryDirectory, target);
        }

        if (entry == null)
        {
            return null;
        }

        LvcProjectService.TrackingOverlay overlay = restartVerifier(repositoryDirectory, entry.overlay(), target, clientLevel, completionListener);
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

    public static TrackingOverlayRevisionTarget resolveTrackingOverlayRevisionTarget(
            Path repositoryDirectory, TrackingOverlayRevision revision) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(revision, "revision");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit head = LvcProjectGitOps.resolveCommit(git.getRepository(), revWalk, "HEAD");

            if (revision == TrackingOverlayRevision.CURRENT)
            {
                return new TrackingOverlayRevisionTarget(head.getName(), head.getName(), false);
            }

            if (head.getParentCount() == 0)
            {
                return new TrackingOverlayRevisionTarget(head.getName(), null, true);
            }

            RevCommit parent = revWalk.parseCommit(head.getParent(0).getId());
            return new TrackingOverlayRevisionTarget(head.getName(), parent.getName(), false);
        }
    }

    public static String trackingOverlayDisplayName(String projectName, TrackingOverlayRevisionTarget target)
    {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(target, "target");

        if (target.airSchematic())
        {
            return projectName + " @ " + shortCommitToken(target.headCommitId()) + "^ (air)";
        }

        return trackingOverlayDisplayNameForCommit(projectName,
                Objects.requireNonNull(target.sourceCommitId(), "sourceCommitId"));
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
        OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);
        Path cacheFile = currentSemanticTrackingCacheFile(repositoryDirectory);

        if (descriptor == null || cacheFile == null || !Files.isRegularFile(cacheFile))
        {
            return false;
        }

        ObjectId head = LvcRepository.resolveHead(repositoryDirectory);

        if (head == null || !head.getName().equals(descriptor.commitId()) ||
                TrackingOverlayRevision.PARENT.serializedName.equals(descriptor.revision()))
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

    public static LvcProjectService.TrackingOverlay addSemanticTrackingOverlay(Path repositoryDirectory,
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

    public static LvcProjectService.TrackingOverlay addSemanticTrackingOverlayForRevision(
            Path repositoryDirectory,
            LitematicaSchematic schematic,
            String siteId,
            LvcSitePlacement placementState,
            String overlayName,
            @Nullable ClientLevel clientLevel,
            @Nullable ICompletionListener completionListener,
            boolean startVerifier,
            TrackingOverlayRevision revision,
            String descriptorCommitId) throws IOException
    {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(descriptorCommitId, "descriptorCommitId");
        OverlayDescriptor descriptor = overlayDescriptor(descriptorCommitId, siteId, placementState.dimension(),
                schematic.getFile(), overlayName, revision);
        return addSemanticTrackingOverlay(repositoryDirectory, schematic, siteId, placementState, overlayName,
                clientLevel, completionListener, startVerifier, descriptor);
    }

    private static LvcProjectService.TrackingOverlay addSemanticTrackingOverlay(
            Path repositoryDirectory,
            LitematicaSchematic schematic,
            String siteId,
            LvcSitePlacement placementState,
            String overlayName,
            @Nullable ClientLevel clientLevel,
            @Nullable ICompletionListener completionListener,
            boolean startVerifier,
            @Nullable OverlayDescriptor descriptor) throws IOException
    {
        BlockPos origin = LvcProjectPositions.blockPosFromList(placementState.origin());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, overlayName, true, true);
        ClientLevel verifierWorld = clientLevel;

        if (clientLevel != null && !LvcMinecraftWorldReader.dimensionId(clientLevel).equals(placementState.dimension()))
        {
            verifierWorld = null;
        }

        LvcProjectService.TrackingOverlay overlay = addTrackingOverlay(repositoryDirectory, placement, verifierWorld, completionListener, startVerifier);
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

    public static TrackingOverlayRevision trackingOverlayRevision(Path repositoryDirectory,
                                                                  SchematicPlacement placement)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(placement, "placement");
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(trackingOverlayKey(repositoryDirectory));
        OverlayDescriptor descriptor = entry != null && entry.overlay().placement() == placement ?
                entry.descriptor() : readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null && descriptor.matchesPlacement(repositoryDirectory, placement) &&
                TrackingOverlayRevision.PARENT.serializedName.equals(descriptor.revision()))
        {
            return TrackingOverlayRevision.PARENT;
        }

        return TrackingOverlayRevision.CURRENT;
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
            ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
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

        BlockPos cachedOrigin = TRACKING_OVERLAY_ORIGIN_CACHE.get(trackingOverlayKey(repositoryDirectory));

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

        BlockPos cachedOrigin = TRACKING_OVERLAY_ORIGIN_CACHE.get(trackingOverlayKey(repositoryDirectory));

        if (cachedOrigin != null)
        {
            return sitePlacementFromOrigin(site, cachedOrigin);
        }

        LvcSitePlacement placementAtPlayer = createSitePlacementAtCurrentPlayer(site);
        TRACKING_OVERLAY_ORIGIN_CACHE.put(
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
            case WRONG_STATE, WRONG_INVENTORIES -> CHANGE_MARKER_STATE;
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
            return LvcProjectService.hasUncommittedChanges(repositoryDirectory);
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
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null)
        {
            return false;
        }

        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(overlay.placement()))
        {
            ACTIVE_TRACKING_OVERLAYS.remove(key);
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
            ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
            LvcDiagnostics.debug("LvcTrackingOverlayService: skipped tracking overlay move repo='{}' origin='{}' error='{}'",
                    repositoryDirectory, origin, e.getMessage());
            return false;
        }
    }

    private static boolean updateTrackingOverlayOriginUnsafe(Path repositoryDirectory, BlockPos origin)
    {
        Path key = trackingOverlayKey(repositoryDirectory);
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;
        SchematicPlacement placement = overlay != null ? overlay.placement() : null;

        if (placement == null || !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(placement))
        {
            ACTIVE_TRACKING_OVERLAYS.remove(key);
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

        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(trackingOverlayKey(repositoryDirectory));
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay == null || overlay.placement() != placement)
        {
            trackOverlay(repositoryDirectory, wrapExistingPlacement(placement), descriptorForExistingPlacement(repositoryDirectory, placement));
            LvcDiagnostics.debug("LvcTrackingOverlayService: attached placement-list tracking overlay repo='{}' placement='{}'",
                    repositoryDirectory, placement.getName());
        }

        focusTrackingOverlayInternal(repositoryDirectory, placement);
        return true;
    }

    private static void trackOverlay(Path repositoryDirectory, LvcProjectService.TrackingOverlay overlay,
                                     @Nullable OverlayDescriptor descriptor)
    {
        cacheTrackingOverlayOrigin(repositoryDirectory, overlay.placement().getOrigin());
        ACTIVE_TRACKING_OVERLAYS.put(trackingOverlayKey(repositoryDirectory), new TrackingOverlayEntry(overlay, descriptor));

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
        TRACKING_OVERLAY_ORIGIN_CACHE.put(trackingOverlayKey(repositoryDirectory), origin);
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
        return repositoryDirectory.toAbsolutePath().normalize();
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
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(key);
        LvcProjectService.TrackingOverlay overlay = entry != null ? entry.overlay() : null;

        if (overlay != null &&
                DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(overlay.placement()))
        {
            return overlay.placement();
        }

        ACTIVE_TRACKING_OVERLAYS.remove(key);
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

    private static LvcProjectService.TrackingOverlay wrapExistingPlacement(SchematicPlacement placement)
    {
        boolean hadVerifier = placement.hasVerifier();
        SchematicVerifier verifier = placement.getSchematicVerifier();
        boolean verifierStarted = hadVerifier && (verifier.isActive() || verifier.isPaused() || verifier.isFinished());
        return new LvcProjectService.TrackingOverlay(placement, verifier, verifierStarted);
    }

    private static void attachCompletionListener(LvcProjectService.TrackingOverlay overlay,
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
    private static TrackingOverlayEntry activeEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
    {
        TrackingOverlayEntry entry = ACTIVE_TRACKING_OVERLAYS.get(trackingOverlayKey(repositoryDirectory));

        if (entry == null)
        {
            return null;
        }

        if (entryMatchesTarget(entry, target))
        {
            return entry;
        }

        ACTIVE_TRACKING_OVERLAYS.remove(trackingOverlayKey(repositoryDirectory));
        return null;
    }

    @Nullable
    private static TrackingOverlayEntry persistedEntryIfCurrent(Path repositoryDirectory, OverlayTarget target) throws IOException
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

        OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

        if (descriptor != null && !descriptor.matches(target))
        {
            return null;
        }

        if (descriptor == null && !isSemanticTrackingCacheCurrent(repositoryDirectory))
        {
            return null;
        }

        return new TrackingOverlayEntry(wrapExistingPlacement(placement), descriptor != null ? descriptor : target.descriptor());
    }

    private static boolean entryMatchesTarget(TrackingOverlayEntry entry, OverlayTarget target) throws IOException
    {
        if (!isMatchingTrackingPlacement(entry.overlay().placement(), target.cacheFile(), target.overlayName()))
        {
            return false;
        }

        if (entry.descriptor() != null)
        {
            return entry.descriptor().matches(target);
        }

        return isSemanticTrackingCacheCurrent(target.repositoryDirectory());
    }

    private static LvcProjectService.TrackingOverlay restartVerifier(Path repositoryDirectory,
                                                                     LvcProjectService.TrackingOverlay overlay,
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
            verifier.startVerification(clientLevel, schematicWorld, overlay.placement(), completionListener);
            verifierStarted = true;
        }
        else
        {
            verifier.reset();
            LvcDiagnostics.debug("LvcTrackingOverlayService: reused tracking overlay '{}' without starting verifier", overlay.placement().getName());
        }

        return new LvcProjectService.TrackingOverlay(overlay.placement(), verifier, verifierStarted);
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
        OverlayDescriptor descriptor = currentOverlayDescriptor(repositoryDirectory, siteId, dimension, cacheFile, overlayName);
        return new OverlayTarget(repositoryDirectory.toAbsolutePath().normalize(), commitId, siteId, dimension,
                cacheFile, overlayName, descriptor);
    }

    private static LvcManifest readCommitManifest(Path repositoryDirectory, String commitId) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit commit = LvcProjectGitOps.resolveCommit(repository, revWalk, commitId);
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
    private static OverlayDescriptor currentOverlayDescriptor(Path repositoryDirectory, String siteId,
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
                TrackingOverlayRevision.CURRENT);
    }

    @Nullable
    private static OverlayDescriptor overlayDescriptor(String commitId, String siteId, String dimension,
                                                       @Nullable Path cacheFile, String overlayName,
                                                       TrackingOverlayRevision revision)
    {
        if (cacheFile == null)
        {
            return null;
        }

        return new OverlayDescriptor(commitId, siteId, dimension,
                cacheFile.toAbsolutePath().normalize().toString(), overlayName, revision.serializedName);
    }

    @Nullable
    private static OverlayDescriptor descriptorForExistingPlacement(Path repositoryDirectory, SchematicPlacement placement)
    {
        try
        {
            OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

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

    private static boolean writeOverlayDescriptor(Path repositoryDirectory, OverlayDescriptor descriptor)
    {
        Path path = semanticTrackingDescriptorFile(repositoryDirectory);

        try
        {
            Files.createDirectories(path.getParent());
            writeStringAtomic(path, GSON.toJson(descriptor));
            return true;
        }
        catch (IOException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: failed to write overlay descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return false;
        }
    }

    private static void writeStringAtomic(Path path, String contents) throws IOException
    {
        Path temp = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;

        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8));

            while (buffer.hasRemaining())
            {
                channel.write(buffer);
            }

            channel.force(true);
        }

        try
        {
            moveReplacing(temp, path);
            moved = true;
            forceDirectory(path.getParent());
        }
        finally
        {
            if (!moved)
            {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory)
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (IOException | UnsupportedOperationException ignored)
        {
        }
    }

    @Nullable
    private static OverlayDescriptor readOverlayDescriptor(Path repositoryDirectory)
    {
        Path path = semanticTrackingDescriptorFile(repositoryDirectory);

        if (!Files.isRegularFile(path))
        {
            return null;
        }

        try
        {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), OverlayDescriptor.class);
        }
        catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayService: ignored invalid overlay descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return null;
        }
    }

    @Nullable
    private static Path currentSemanticTrackingCacheFile(Path repositoryDirectory)
    {
        OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

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

        OverlayDescriptor descriptor = readOverlayDescriptor(repositoryDirectory);

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

    private static Path semanticTrackingDescriptorFile(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(TRACKING_OVERLAY_CACHE_DIRECTORY)
                .resolve(TRACKING_OVERLAY_DESCRIPTOR_FILE)
                .normalize();
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

    private static LvcProjectService.TrackingOverlay addTrackingOverlay(Path repositoryDirectory,
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
            verifier.startVerification(clientLevel, schematicWorld, placement, completionListener);
            verifierStarted = true;
        }

        LvcDiagnostics.debug("LvcTrackingOverlayService: added tracking overlay repo='{}' placement='{}' startVerifier={} verifierStarted={} colorPalette='added=green removed=red changed_state=yellow wrong_block=orange'",
                repositoryDirectory, placement.getName(), startVerifier, verifierStarted);

        return new LvcProjectService.TrackingOverlay(placement, verifier, verifierStarted);
    }

    private record TrackingOverlayEntry(LvcProjectService.TrackingOverlay overlay, @Nullable OverlayDescriptor descriptor)
    {
    }

    private record OverlayTarget(Path repositoryDirectory, String commitId, String siteId, String dimension,
                                 Path cacheFile, String overlayName, OverlayDescriptor descriptor)
    {
    }

    private record OverlayDescriptor(String commitId, String siteId, String dimension,
                                     String cacheFile, String overlayName, @Nullable String revision)
    {
        private boolean matches(OverlayTarget target)
        {
            Path descriptorCacheFile = this.cacheFilePath();
            return Objects.equals(this.commitId, target.commitId()) &&
                    Objects.equals(this.siteId, target.siteId()) &&
                    Objects.equals(this.dimension, target.dimension()) &&
                    descriptorCacheFile != null &&
                    pathsEqual(target.cacheFile(), descriptorCacheFile) &&
                    Objects.equals(this.overlayName, target.overlayName());
        }

        private boolean matchesPlacement(Path repositoryDirectory, SchematicPlacement placement)
        {
            Path descriptorCacheFile = this.cacheFilePath();
            return descriptorCacheFile != null &&
                    isTrackingCacheForRepository(repositoryDirectory, descriptorCacheFile) &&
                    Objects.equals(this.overlayName, placement.getName()) &&
                    pathsEqual(descriptorCacheFile, placement.getSchematicFile());
        }

        @Nullable
        private Path cacheFilePath()
        {
            return this.cacheFile == null ? null : Path.of(this.cacheFile).toAbsolutePath().normalize();
        }
    }
}
