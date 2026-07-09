package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcSemanticOverlayCacheIntegrationTest
{
    private LvcSemanticOverlayCacheIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("tracking overlay display name includes short head commit", LvcSemanticOverlayCacheIntegrationTest::trackingOverlayDisplayNameIncludesShortHeadCommit);
        IntegrationTestSupport.run("semantic tracking overlay cache is file backed", LvcSemanticOverlayCacheIntegrationTest::semanticTrackingOverlayCacheIsFileBacked);
        IntegrationTestSupport.run("corrupt semantic tracking overlay descriptor is recoverable", LvcSemanticOverlayCacheIntegrationTest::corruptSemanticTrackingOverlayDescriptorIsRecoverable);
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
