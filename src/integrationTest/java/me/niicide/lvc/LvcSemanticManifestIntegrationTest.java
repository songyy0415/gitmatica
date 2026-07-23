package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.areaSelectionFromBoxes;
import static me.niicide.lvc.LvcIntegrationFixtures.boxJson;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcSemanticTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import fi.dy.masa.litematica.selection.AreaSelection;
import me.niicide.lvc.capture.LvcCaptureEngine;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcSemanticManifestIntegrationTest
{
    private LvcSemanticManifestIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic manifest round trip", LvcSemanticManifestIntegrationTest::semanticManifestAndLocalStateRoundTrip);
        IntegrationTestSupport.run("semantic manifest allows overlapping regions as tracked union masks", LvcSemanticManifestIntegrationTest::semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks);
        IntegrationTestSupport.run("project service maps selection to semantic site", LvcSemanticManifestIntegrationTest::projectServiceMapsSelectionToSemanticSite);
        IntegrationTestSupport.run("project service updates regions from existing placement origin", LvcSemanticManifestIntegrationTest::projectServiceUpdatesRegionsFromExistingPlacementOrigin);
        IntegrationTestSupport.run("tracking overlay first load uses seeded project origin", LvcSemanticManifestIntegrationTest::trackingOverlayFirstLoadUsesSeededProjectOrigin);
        IntegrationTestSupport.run("project service creates empty browser project without commit", LvcSemanticManifestIntegrationTest::projectServiceCreatesEmptyBrowserProjectWithoutCommit);
        IntegrationTestSupport.run("project summary reports no origin when placement is not loaded", LvcSemanticManifestIntegrationTest::projectSummaryToleratesMissingLocalStateWithoutCreatingIt);
    }

    private static void semanticManifestAndLocalStateRoundTrip() throws Exception
    {
        String objectId = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        LvcManifest.Site overworldMain = new LvcManifest.Site(
                "overworld_main",
                "Overworld Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("storage", "Storage", List.of(0, 0, 0), List.of(32, 16, 32))),
                Map.of("0,0,0", objectId)
        );
        LvcManifest.Site overworldRemote = new LvcManifest.Site(
                "overworld_remote",
                "Overworld Remote",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("dropoff", "Dropoff", List.of(0, 0, 0), List.of(16, 16, 16))),
                Map.of()
        );
        LvcManifest manifest = LvcManifest.create("Gold Farm", List.of(overworldMain, overworldRemote));
        String json = manifest.toJson();

        IntegrationTestSupport.assertEquals(
                Set.of("format", "name", "sites"),
                JsonParser.parseString(json).getAsJsonObject().keySet(),
                "manifest should serialize only supported top-level metadata"
        );
        IntegrationTestSupport.assertTrue(!json.contains("\"content\""), "manifest should not serialize internal content settings");
        IntegrationTestSupport.assertTrue(!json.contains("\"chunk_size\""), "manifest should not expose internal chunk size");
        IntegrationTestSupport.assertTrue(!json.contains("\"hash_index_format\""), "manifest should not expose internal hash index format");
        IntegrationTestSupport.assertTrue(json.contains("\"hash_index\""), "manifest should reference external hash index");
        IntegrationTestSupport.assertTrue(!json.contains("\"full_hashes\""), "manifest should not serialize full hashes");
        IntegrationTestSupport.assertTrue(!json.contains("\"chunks\""), "manifest should not serialize old chunks key");
        IntegrationTestSupport.assertTrue(!json.contains("\"tracked_hashes\""), "manifest should not serialize tracked hashes");

        LvcManifest decodedManifest = LvcManifest.fromJson(json);
        IntegrationTestSupport.assertEquals("Gold Farm", decodedManifest.name(), "manifest name");
        IntegrationTestSupport.assertEquals(LvcManifest.Content.defaultContent(), decodedManifest.content(), "manifest should hydrate internal content defaults");
        IntegrationTestSupport.assertEquals(2, decodedManifest.sites().size(), "same dimension multi-site manifest should be valid");
        IntegrationTestSupport.assertEquals("indexes/overworld_main.lvcidx", decodedManifest.sites().get(0).hashIndex(), "manifest should round-trip hash index reference");
        IntegrationTestSupport.assertEquals(0, decodedManifest.sites().get(0).fullHashes().size(), "manifest JSON alone should not carry full hashes");
        IntegrationTestSupport.assertEquals(0, decodedManifest.sites().get(0).trackedHashes().size(), "manifest JSON alone should not carry tracked hashes");

        String userEditedContentJson = json.replaceFirst(
                "\"sites\"",
                "\"content\": {\"chunk_format\": \"user-edit\", \"hash_index_format\": \"user-edit\", \"hash\": \"md5\", \"chunk_size\": [99, 99, 99]},\n  \"sites\""
        );
        IntegrationTestSupport.assertTrue(LvcManifest.hasSerializedContent(userEditedContentJson), "old/user-edited content block should be detectable for diagnostics");
        LvcManifest userEditedContentManifest = LvcManifest.fromJson(userEditedContentJson);
        IntegrationTestSupport.assertEquals(LvcManifest.Content.defaultContent(), userEditedContentManifest.content(), "manifest JSON content block must not override internal content defaults");
        IntegrationTestSupport.assertTrue(!userEditedContentManifest.toJson().contains("\"content\""), "rewritten manifest should drop old content block");

    }

    private static void semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-overlap-union-");
        LvcManifest manifest = LvcManifest.create("Overlap", List.of(new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(
                        new LvcManifest.Region("a", "A", List.of(0, 0, 0), List.of(2, 1, 1)),
                        new LvcManifest.Region("b", "B", List.of(1, 0, 0), List.of(2, 1, 1))
                ),
                Map.of()
        )));
        LvcManifest.Site site = manifest.site("main");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");

        LvcCaptureEngine.Result result = LvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        LvcChunk chunk = readOnlyCapturedChunk(repoDir, result);

        IntegrationTestSupport.assertEquals(2, site.regions().size(), "overlapping region definitions should be preserved");
        IntegrationTestSupport.assertEquals(3, chunk.trackedCount(), "overlapping regions should track the union, not duplicate shared blocks");
        IntegrationTestSupport.assertEquals(3, reader.requestedPositions.size(), "overlap position should be read once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(0), "first region start should be tracked");
        IntegrationTestSupport.assertTrue(chunk.isTracked(1), "shared overlap should be tracked once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(2), "second region end should be tracked");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(0), "first tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:dirt", chunk.blockStateAtTrackedOrdinal(1), "overlapped tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(2), "last tracked block state");
    }

    private static void projectServiceMapsSelectionToSemanticSite()
    {
        AreaSelection selection = areaSelectionFromBoxes(
                "Farm",
                List.of(
                        boxJson("Main Storage", new BlockPos(10, 64, 10), new BlockPos(11, 65, 11)),
                        boxJson("Main/Storage", new BlockPos(20, 70, 20), new BlockPos(20, 70, 20))
                )
        );

        LvcManifest.Site site = LvcProjectService.createMainSiteFromSelection("Farm", "minecraft:overworld", selection);
        LvcSitePlacement placement = LvcProjectService.createSitePlacement(selection.getEffectiveOrigin(), "minecraft:overworld");

        IntegrationTestSupport.assertEquals("main", site.id(), "MVP creates one active site");
        IntegrationTestSupport.assertEquals("minecraft:overworld", site.dimension(), "site dimension");
        IntegrationTestSupport.assertEquals(2, site.regions().size(), "selection boxes become explicit regions");
        IntegrationTestSupport.assertEquals("main_storage", site.regions().get(0).id(), "region id should be stable and safe");
        IntegrationTestSupport.assertEquals("main_storage_2", site.regions().get(1).id(), "duplicate region ids should be uniqued");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), site.regions().get(0).min(), "first region should be relative to placement origin");
        IntegrationTestSupport.assertEquals(List.of(2, 2, 2), site.regions().get(0).size(), "region size should be inclusive of both corners");
        IntegrationTestSupport.assertEquals(List.of(10, 64, 10), placement.origin(), "placement stores the world origin");
    }

    private static void projectServiceUpdatesRegionsFromExistingPlacementOrigin()
    {
        LvcManifest.Region existing = new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1));
        AreaSelection expandedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Line", new BlockPos(10, 64, 10), new BlockPos(26, 64, 10)))
        );

        List<LvcManifest.Region> expanded = LvcProjectService.createRegionsFromSelection(expandedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", expanded.get(0).id(), "same region name should preserve id while resizing");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), expanded.get(0).min(), "update areas should stay relative to existing placement origin");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.get(0).size(), "expanded region size");

        AreaSelection renamedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Renamed Line", new BlockPos(10, 64, 10), new BlockPos(10, 64, 10)))
        );
        List<LvcManifest.Region> renamed = LvcProjectService.createRegionsFromSelection(renamedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", renamed.get(0).id(), "same bounds should preserve id while renaming");
        IntegrationTestSupport.assertEquals("Renamed Line", renamed.get(0).name(), "region display name should update");
    }

    private static void trackingOverlayFirstLoadUsesSeededProjectOrigin() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-overlay-seeded-origin-");
        AreaSelection selection = areaSelectionFromBoxes(
                "Farm",
                List.of(
                        boxJson("Storage", new BlockPos(20, 70, 20), new BlockPos(20, 70, 20)),
                        boxJson("Barn", new BlockPos(10, 64, 10), new BlockPos(11, 64, 11))
                )
        );
        LvcManifest.Site site = LvcProjectService.createMainSiteFromSelection("Farm", "minecraft:overworld", selection);
        LvcSitePlacement placement = LvcProjectService.createSitePlacement(selection.getEffectiveOrigin(), "minecraft:overworld");

        LvcTrackingOverlayService.seedTrackingOverlayOrigin(repoDir, placement);
        LvcSitePlacement resolved = LvcTrackingOverlayService.resolveSitePlacementForTrackingOverlay(repoDir, site);

        IntegrationTestSupport.assertEquals(List.of(10, 64, 10), resolved.origin(), "first overlay load should use selection-derived project origin");
    }

    private static void projectServiceCreatesEmptyBrowserProjectWithoutCommit() throws Exception
    {
        Path gameDir = Files.createTempDirectory("lvc-empty-project-game-");
        BlockPos origin = new BlockPos(12, 65, -4);
        LvcProjectService.EmptyProjectResult created = LvcProjectService.createEmptyProject(gameDir, "Manual Project", "minecraft:overworld");
        Path repoDir = created.repositoryDirectory();

        IntegrationTestSupport.assertEquals("Manual Project", created.projectName(), "manual project display name");
        IntegrationTestSupport.assertTrue(Files.isDirectory(repoDir.resolve(".git")), "manual project should initialize Git repository");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(repoDir.resolve(LvcSemanticRepository.MANIFEST)), "manual project should write manifest");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "manual project should not write local.json");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("README.md")), "manual project should not generate README");
        IntegrationTestSupport.assertEquals(null, LvcRepository.resolveHead(repoDir), "manual project should start without an initial commit");
        IntegrationTestSupport.assertEquals(0, LvcProjectService.listCommits(repoDir).size(), "manual project history should start empty");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, git.getRepository().getFullBranch(), "manual project should initialize the default branch");
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);

        IntegrationTestSupport.assertEquals(0, manifest.site("main").regions().size(), "manual project should start with no regions");
        IntegrationTestSupport.assertEquals(0, manifest.site("main").fullHashes().size(), "manual project should start with no full hashes");
        IntegrationTestSupport.assertEquals(0, manifest.site("main").trackedHashes().size(), "manual project should start with no tracked hashes");

        try
        {
            LvcProjectService.createSemanticRegion(repoDir, "First Area", BlockPos.ZERO, new BlockPos(1, 1, 1));
            throw new AssertionError("manual project region edit should require a loaded placement");
        }
        catch (LvcUserActionException e)
        {
            IntegrationTestSupport.assertEquals(LvcUserActionException.Reason.MISSING_PLACEMENT, e.reason(), "manual project region edit should require a loaded placement");
        }

        LvcManifest withRegion = manifest.withSite(
                "main",
                manifest.site("main").withRegions(List.of(new LvcManifest.Region("first_area", "First Area", List.of(0, 0, 0), List.of(2, 2, 2))))
        );
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir, withRegion);
        LvcSemanticRepository.CommitResult firstCommit = LvcSemanticRepository.commitSite(
                repoDir,
                withRegion,
                "main",
                placementAt(origin.getX(), origin.getY(), origin.getZ()),
                new FakeWorldReader("minecraft:stone"),
                player("ManualProject"),
                "first version"
        );

        IntegrationTestSupport.assertNotNull(firstCommit.commit(), "manual project first region save should create first commit");
        IntegrationTestSupport.assertEquals(firstCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "manual project first commit should become HEAD");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").regions().size(), "first commit should keep the new region");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").fullHashes().size(), "first commit should capture tracked content");
        IntegrationTestSupport.assertEquals(firstCommit.manifest().site("main").fullHashes().keySet(), firstCommit.manifest().site("main").trackedHashes().keySet(), "first commit should track hash per full hash");
    }

    private static void projectSummaryToleratesMissingLocalStateWithoutCreatingIt() throws Exception
    {
        Path gameDir = Files.createTempDirectory("lvc-summary-missing-local-game-");
        Path repoDir = LvcProjectService.createEmptyProject(gameDir, "Summary Project", "minecraft:overworld").repositoryDirectory();

        LvcProjectService.ProjectSummary summary = LvcProjectService.projectSummary(new LvcProjectService.Project("Summary Project", repoDir));

        IntegrationTestSupport.assertEquals("Summary Project", summary.name(), "summary should still read manifest name");
        IntegrationTestSupport.assertEquals(null, summary.origin(), "summary should report unknown origin when placement is not loaded");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "summary should not generate local.json");
    }
}
