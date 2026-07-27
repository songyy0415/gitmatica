package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.google.gson.JsonObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import net.minecraft.world.level.block.Blocks;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.overlay.LvcTrackingOverlayService.TrackingOverlayManifestSource;
import me.niicide.lvc.overlay.LvcTrackingOverlayService.TrackingOverlayRevision;
import me.niicide.lvc.overlay.LvcTrackingOverlayService.TrackingOverlayRevisionTarget;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcSemanticOverlayCacheIntegrationTest
{
    private LvcSemanticOverlayCacheIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("tracking overlay display name includes short head commit", LvcSemanticOverlayCacheIntegrationTest::trackingOverlayDisplayNameIncludesShortHeadCommit);
        IntegrationTestSupport.run("tracking overlay revision targets current parent and root air", LvcSemanticOverlayCacheIntegrationTest::trackingOverlayRevisionTargetsCurrentParentAndRootAir);
        IntegrationTestSupport.run("dirty sub-region definitions remain the current overlay source", LvcSemanticOverlayCacheIntegrationTest::dirtySubRegionDefinitionsRemainCurrentOverlaySource);
        IntegrationTestSupport.run("keep-changes undo combines working bounds with parent content", LvcSemanticOverlayCacheIntegrationTest::keepChangesUndoCombinesWorkingBoundsWithParentContent);
        IntegrationTestSupport.run("semantic tracking overlay cache is file backed", LvcSemanticOverlayCacheIntegrationTest::semanticTrackingOverlayCacheIsFileBacked);
        IntegrationTestSupport.run("current overlay cache rejects parent preview descriptors", LvcSemanticOverlayCacheIntegrationTest::currentOverlayCacheRejectsParentPreviewDescriptors);
        IntegrationTestSupport.run("corrupt semantic tracking overlay descriptor is recoverable", LvcSemanticOverlayCacheIntegrationTest::corruptSemanticTrackingOverlayDescriptorIsRecoverable);
    }

    private static void currentOverlayCacheRejectsParentPreviewDescriptors() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-parent-overlay-cache-");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(
                repoDir, "Parent Cache", singleLineSite(1), placementAt(0, 0, 0),
                new FakeWorldReader("minecraft:stone"), player("ParentCache"));
        String commitId = init.commit().getName();
        String overlayName = "Parent Cache @ " + commitId.substring(0, 8);
        Path cacheFile = LvcProjectService.writeSemanticTrackingCacheFile(
                repoDir, init.manifest(), "main", placementAt(0, 0, 0), overlayName);
        Path descriptor = repoDir.toAbsolutePath().normalize().resolve(".git").resolve("lvc-cache")
                .resolve("tracking-overlay.json");
        JsonObject json = new JsonObject();
        json.addProperty("commitId", commitId);
        json.addProperty("siteId", "main");
        json.addProperty("dimension", "minecraft:overworld");
        json.addProperty("cacheFile", cacheFile.toAbsolutePath().normalize().toString());
        json.addProperty("overlayName", overlayName);
        json.addProperty("revision", "current");
        json.addProperty("definitionId", LvcSemanticRepository.trackingOverlayDefinitionId(init.manifest()));
        Files.writeString(descriptor, json.toString(), StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "current revision descriptor should allow current cache reuse");

        json.remove("definitionId");
        Files.writeString(descriptor, json.toString(), StandardCharsets.UTF_8);
        IntegrationTestSupport.assertTrue(!LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "descriptors without an overlay definition identity should rebuild once");

        json.addProperty("definitionId", LvcSemanticRepository.trackingOverlayDefinitionId(init.manifest()));
        json.remove("revision");
        Files.writeString(descriptor, json.toString(), StandardCharsets.UTF_8);
        IntegrationTestSupport.assertTrue(LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "existing current descriptors without a revision should remain reusable");

        json.addProperty("revision", "parent");
        Files.writeString(descriptor, json.toString(), StandardCharsets.UTF_8);
        IntegrationTestSupport.assertTrue(!LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "parent revision descriptor must not allow current cache reuse");
    }

    private static void dirtySubRegionDefinitionsRemainCurrentOverlaySource() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-dirty-overlay-source-");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(
                repoDir, "Dirty Overlay", singleLineSite(1), placementAt(0, 0, 0),
                new FakeWorldReader("minecraft:stone"), player("DirtyOverlay"));
        LvcManifest dirtyManifest = init.manifest().withSite(
                "main",
                init.manifest().site("main").withRegions(List.of(
                        new LvcManifest.Region("Expanded", List.of(2, 3, 4), List.of(5, 6, 7))
                ))
        );
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir, dirtyManifest);

        TrackingOverlayManifestSource dirtySource =
                LvcTrackingOverlayService.resolveTrackingOverlayManifestSource(repoDir);

        IntegrationTestSupport.assertTrue(dirtySource.committedHead(),
                "dirty sub-region definitions should read block content from committed HEAD");
        IntegrationTestSupport.assertTrue(dirtySource.workingDefinitions(),
                "dirty sub-region definitions should remain the overlay geometry source");
        IntegrationTestSupport.assertEquals(
                dirtyManifest.site("main").regions(),
                dirtySource.manifest().site("main").regions(),
                "working sub-region definitions should remain visible before commit"
        );
        IntegrationTestSupport.assertEquals(
                init.manifest().site("main").fullHashes(),
                dirtySource.manifest().site("main").fullHashes(),
                "dirty sub-region definitions should not replace committed overlay block content"
        );

        LvcProjectService.resetWorkingTreeToHead(repoDir);
        Files.writeString(repoDir.resolve("notes.txt"), "baseline", StandardCharsets.UTF_8);

        try (Git git = Git.open(repoDir.toFile()))
        {
            git.add().addFilepattern("notes.txt").call();
            git.commit()
                    .setMessage("Add project notes")
                    .setAuthor("DirtyOverlay", "dirty-overlay@example.invalid")
                    .setCommitter("DirtyOverlay", "dirty-overlay@example.invalid")
                    .call();
        }

        Files.writeString(repoDir.resolve("notes.txt"), "modified", StandardCharsets.UTF_8);
        TrackingOverlayManifestSource unrelatedSource =
                LvcTrackingOverlayService.resolveTrackingOverlayManifestSource(repoDir);

        IntegrationTestSupport.assertTrue(unrelatedSource.committedHead(),
                "unrelated Git changes should retain committed overlay contents");
        IntegrationTestSupport.assertTrue(!unrelatedSource.workingDefinitions(),
                "unrelated Git changes should retain committed overlay definitions");
    }

    private static void keepChangesUndoCombinesWorkingBoundsWithParentContent() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-undo-expanded-overlay-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult initial = LvcSemanticRepository.initProject(
                repoDir,
                "Undo Expanded Overlay",
                singleLineSite(1),
                placementAt(0, 0, 0),
                reader,
                player("UndoExpandedOverlay")
        );

        LvcManifest expandedDefinition = initial.manifest().withSite(
                "main",
                initial.manifest().site("main").withRegions(List.of(
                        new LvcManifest.Region("Line", List.of(0, 0, 0), List.of(2, 1, 1))
                ))
        );
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir, expandedDefinition);
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult expanded = LvcSemanticRepository.commitSite(
                repoDir,
                LvcSemanticRepository.readManifest(repoDir),
                "main",
                placementAt(0, 0, 0),
                reader,
                player("UndoExpandedOverlay"),
                "expand tracked area"
        );
        IntegrationTestSupport.assertNotNull(expanded.commit(), "expanded-area fixture commit");

        LvcProjectService.undoLatestCommitKeepChanges(repoDir);
        TrackingOverlayManifestSource source =
                LvcTrackingOverlayService.resolveTrackingOverlayManifestSource(repoDir);

        IntegrationTestSupport.assertTrue(source.committedHead(),
                "keep-changes overlay should load block content from the parent commit");
        IntegrationTestSupport.assertTrue(source.workingDefinitions(),
                "keep-changes overlay should retain the expanded working bounds");
        IntegrationTestSupport.assertEquals(
                expanded.manifest().site("main").regions(),
                source.manifest().site("main").regions(),
                "keep-changes overlay should preserve expanded sub-region definitions"
        );
        IntegrationTestSupport.assertEquals(
                initial.manifest().site("main").fullHashes(),
                source.manifest().site("main").fullHashes(),
                "keep-changes overlay should use parent chunk content"
        );

        try (Git git = Git.open(repoDir.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit sourceCommit = LvcProjectGitOps.resolveCommit(
                    git.getRepository(),
                    revWalk,
                    source.commitId()
            );
            fi.dy.masa.litematica.schematic.LitematicaSchematic schematic =
                    LvcSemanticSchematicBuilder.buildSchematic(
                            source.manifest(),
                            "main",
                            placementAt(0, 0, 0),
                            objectId ->
                            {
                                byte[] bytes = LvcProjectGitOps.readCommitFile(
                                        git.getRepository(),
                                        sourceCommit,
                                        LvcChunkStore.objectRepositoryPath(objectId)
                                );

                                if (bytes == null)
                                {
                                    throw new java.io.IOException("Missing committed test object: " + objectId);
                                }

                                return bytes;
                            }
                    );
            fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container =
                    schematic.getSubRegionContainer("Line");

            IntegrationTestSupport.assertNotNull(container, "keep-changes overlay region container");
            IntegrationTestSupport.assertEquals(
                    Blocks.STONE.defaultBlockState(),
                    container.get(0, 0, 0),
                    "parent-tracked block should retain parent content"
            );
            IntegrationTestSupport.assertEquals(
                    Blocks.AIR.defaultBlockState(),
                    container.get(1, 0, 0),
                    "newly expanded position should become tracked air after keep-changes undo"
            );
        }
    }

    private static void trackingOverlayRevisionTargetsCurrentParentAndRootAir() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-overlay-revisions-");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(
                repoDir, "Revision Overlay", singleLineSite(1), placementAt(0, 0, 0),
                new FakeWorldReader("minecraft:stone"), player("OverlayRevision"));

        TrackingOverlayRevisionTarget currentRoot = LvcTrackingOverlayService.resolveTrackingOverlayRevisionTarget(
                repoDir, TrackingOverlayRevision.CURRENT);
        TrackingOverlayRevisionTarget parentRoot = LvcTrackingOverlayService.resolveTrackingOverlayRevisionTarget(
                repoDir, TrackingOverlayRevision.PARENT);

        IntegrationTestSupport.assertEquals(init.commit().getName(), currentRoot.sourceCommitId(),
                "root current overlay source");
        IntegrationTestSupport.assertTrue(!currentRoot.airSchematic(), "root current overlay should contain commit blocks");
        IntegrationTestSupport.assertEquals(null, parentRoot.sourceCommitId(), "root parent overlay should not have a source commit");
        IntegrationTestSupport.assertTrue(parentRoot.airSchematic(), "root parent overlay should be air");

        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(
                repoDir, init.manifest(), "main", placementAt(0, 0, 0),
                new FakeWorldReader("minecraft:dirt"), player("OverlayRevision"), "change block");
        IntegrationTestSupport.assertNotNull(update.commit(), "revision fixture update commit");

        TrackingOverlayRevisionTarget current = LvcTrackingOverlayService.resolveTrackingOverlayRevisionTarget(
                repoDir, TrackingOverlayRevision.CURRENT);
        TrackingOverlayRevisionTarget parent = LvcTrackingOverlayService.resolveTrackingOverlayRevisionTarget(
                repoDir, TrackingOverlayRevision.PARENT);

        IntegrationTestSupport.assertEquals(update.commit().getName(), current.sourceCommitId(),
                "current overlay source after update");
        IntegrationTestSupport.assertEquals(init.commit().getName(), parent.sourceCommitId(),
                "parent overlay should use first parent");
        IntegrationTestSupport.assertTrue(!parent.airSchematic(), "non-root parent overlay should contain parent blocks");
    }

    private static void trackingOverlayDisplayNameIncludesShortHeadCommit() throws Exception
    {
        Path emptyDir = Files.createTempDirectory("lvc-overlay-name-empty-");
        IntegrationTestSupport.assertEquals("Display Project", LvcProjectService.trackingOverlayDisplayName(emptyDir, "Display Project"), "overlay name without HEAD");

        Path repoDir = Files.createTempDirectory("lvc-overlay-name-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Display Project", singleLineSite(1), placementAt(0, 0, 0), reader, player("OverlayName"));
        String shortHead = init.commit().getId().getName().substring(0, 8);
        String expectedName = "Display Project @ " + shortHead;

        IntegrationTestSupport.assertEquals(expectedName, LvcProjectService.trackingOverlayDisplayName(repoDir, "Display Project"), "overlay name with HEAD");
    }

    private static void semanticTrackingOverlayCacheIsFileBacked() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-file-overlay-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "File Overlay", singleLineSite(1), placementAt(0, 0, 0), reader, player("FileOverlay"));

        Path expectedDirectory = repoDir.toAbsolutePath().normalize().resolve(".git").resolve("lvc-cache").normalize();
        Path actualFile = LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "File Overlay");
        String actualFileName = actualFile.getFileName().toString();

        IntegrationTestSupport.assertEquals(expectedDirectory, actualFile.getParent(), "semantic tracking overlay cache should use the local Git cache path");
        IntegrationTestSupport.assertTrue(actualFileName.startsWith("tracking-overlay-"), "semantic tracking overlay cache should use a generated tracking overlay file name");
        IntegrationTestSupport.assertTrue(actualFileName.endsWith(".litematic"), "semantic tracking overlay cache should be a litematic file");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(actualFile), "semantic tracking overlay cache file should exist");

        Path secondFile = LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "File Overlay");
        IntegrationTestSupport.assertTrue(!actualFile.equals(secondFile), "semantic tracking overlay cache writes should not overwrite the previous file path");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(secondFile), "second semantic tracking overlay cache file should exist");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "semantic overlay cache under .git should not dirty the project");
        }
    }

    private static void corruptSemanticTrackingOverlayDescriptorIsRecoverable() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-corrupt-overlay-descriptor-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Corrupt Overlay", singleLineSite(1), placementAt(0, 0, 0), reader, player("CorruptOverlay"));
        Path descriptor = repoDir.toAbsolutePath().normalize().resolve(".git").resolve("lvc-cache").resolve("tracking-overlay.json");

        LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "Corrupt Overlay");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "{", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(!LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "corrupt semantic tracking overlay descriptor should be ignored so the overlay can rebuild");
    }
}
