package me.niicide.lvc;

import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.git.LvcGitTreeReader;
import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.niicide.lvc.LvcSemanticTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.capture.LvcCaptureEngine;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcHashIndexCodec;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticObjectPruner;

final class LvcSemanticRepositoryLifecycleIntegrationTest
{
    private LvcSemanticRepositoryLifecycleIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic repository init commits manifest and objects", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryInitCommitsManifestAndObjects);
        IntegrationTestSupport.run("semantic repository no-op commit reports no changes", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryNoOpCommitReportsNoChanges);
        IntegrationTestSupport.run("semantic lossy commit empties previous block entities", LvcSemanticRepositoryLifecycleIntegrationTest::semanticLossyCommitEmptiesPreviousBlockEntities);
        IntegrationTestSupport.run("semantic repository commit updates only changed full hash", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryCommitUpdatesOnlyChangedChunkReference);
        IntegrationTestSupport.run("semantic repository commit prunes replaced object from current head", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryCommitPrunesReplacedObjectFromCurrentHead);
        IntegrationTestSupport.run("semantic repository commit keeps shared live object candidate", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryCommitKeepsSharedLiveObjectCandidate);
        IntegrationTestSupport.run("semantic object pruner aborts before deleting when new object is missing", LvcSemanticRepositoryLifecycleIntegrationTest::semanticObjectPrunerAbortsBeforeDeletingWhenNewObjectIsMissing);
        IntegrationTestSupport.run("semantic repository update areas changes regions and full hashes", LvcSemanticRepositoryLifecycleIntegrationTest::semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs);
    }

    private static void semanticRepositoryInitCommitsManifestAndObjects() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-init-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult result = LvcSemanticRepository.initProject(repoDir, "Semantic Init", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticInit"));

        IntegrationTestSupport.assertNotNull(result.commit(), "semantic init should create a commit");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(LvcSemanticRepository.MANIFEST), "\"format\": \"lvc-manifest-v1\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(LvcSemanticRepository.MANIFEST), "\"hash_index\"");
        String manifestText = Files.readString(repoDir.resolve(LvcSemanticRepository.MANIFEST));
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"content\""), "lvc.json should not expose internal content settings");
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"full_hashes\""), "lvc.json should not contain full hash map");
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"tracked_hashes\""), "lvc.json should not contain tracked hash map");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "semantic init should not write local.json");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("README.md")), "semantic init should not generate README");

        String objectId = result.manifest().site("main").fullHashes().get("0,0,0");
        Path hashIndexPath = repoDir.resolve(result.manifest().site("main").hashIndex());
        IntegrationTestSupport.assertTrue(Files.isRegularFile(hashIndexPath), "semantic init should write hash index");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").fullHashes(), LvcHashIndexCodec.read(hashIndexPath).fullHashes(), "hash index should store full hashes");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").trackedHashes(), LvcHashIndexCodec.read(hashIndexPath).trackedHashes(), "hash index should store tracked hashes");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, objectId)), "semantic init should write captured chunk object");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").fullHashes().keySet(), result.manifest().site("main").trackedHashes().keySet(), "semantic init should write tracked hash per full hash");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId headId = repository.resolve(Constants.HEAD);
            IntegrationTestSupport.assertNotNull(headId, "semantic repo HEAD");
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "semantic repo should be clean after init");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit commit = revWalk.parseCommit(headId);
                Set<String> files = committedFiles(repository, commit);

                IntegrationTestSupport.assertTrue(files.contains(LvcSemanticRepository.MANIFEST), "semantic commit should include lvc.json");
                IntegrationTestSupport.assertTrue(!files.contains("README.md"), "semantic commit should not include generated README");
                IntegrationTestSupport.assertTrue(files.stream().anyMatch(path -> path.endsWith(LvcHashIndexCodec.EXTENSION)), "semantic commit should include hash index");
                IntegrationTestSupport.assertTrue(files.stream().anyMatch(path -> path.endsWith(LvcChunkStore.EXTENSION)), "semantic commit should include chunk object");
                IntegrationTestSupport.assertTrue(!files.contains("local.json"), "semantic commit must not include local.json");

                String rawCommit = new String(repository.open(headId).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nlvc-version 1\n"), "semantic commit should include LVC metadata");
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nx-created-by lvc\n"), "semantic commit should include created-by metadata");
            }
        }
    }

    private static void semanticRepositoryNoOpCommitReportsNoChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-noop-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Noop", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticNoop"));
        ObjectId headBefore = LvcRepository.resolveHead(repoDir);

        LvcSemanticRepository.CommitResult noOp = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main", placementAt(0, 0, 0), reader, player("SemanticNoop"), "same content");

        IntegrationTestSupport.assertEquals(null, noOp.commit(), "same semantic content should not create a commit");
        IntegrationTestSupport.assertEquals(headBefore, LvcRepository.resolveHead(repoDir), "no-op semantic commit should not move HEAD");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").fullHashes(), noOp.manifest().site("main").fullHashes(), "no-op semantic capture should keep full hashes");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").trackedHashes(), noOp.manifest().site("main").trackedHashes(), "no-op semantic capture should keep tracked hashes");
    }

    private static void semanticLossyCommitEmptiesPreviousBlockEntities() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-lossy-empty-be-");
        String furnace = LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState());
        FakeWorldReader servuxLikeReader = new FakeWorldReader(furnace);
        servuxLikeReader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:diamond", "Captured Inventory"));
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Lossy Empty BE",
                singleLineSite(1), placementAt(0, 0, 0), servuxLikeReader, player("LossyEmptyBe"));
        ObjectId headBefore = LvcRepository.resolveHead(repoDir);
        int initialBlockEntityRecords = LvcSemanticRepository.countBlockEntityRecords(repoDir, init.manifest().site("main"));

        FakeWorldReader commandLikeReader = new FakeWorldReader(furnace);
        LvcSemanticRepository.CommitResult lossy = LvcSemanticRepository.commitSite(repoDir, init.manifest(),
                "main", placementAt(0, 0, 0), commandLikeReader, player("LossyEmptyBe"), "capture without servux");

        IntegrationTestSupport.assertNotNull(lossy.commit(), "lossy no-Servux capture should commit inventory removal");
        IntegrationTestSupport.assertTrue(!headBefore.equals(lossy.commit().getId()), "lossy inventory-empty commit should move HEAD");
        IntegrationTestSupport.assertEquals(1, initialBlockEntityRecords, "initial Servux-like capture should store the inventory payload");
        IntegrationTestSupport.assertEquals(0, LvcSemanticRepository.countBlockEntityRecords(repoDir, lossy.manifest().site("main")),
                "command-like capture should store the container as empty/no block entity payload");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").trackedHashes().equals(lossy.manifest().site("main").trackedHashes()),
                "inventory removal should be a tracked semantic diff");
        LvcChunk emptied = readOnlyCapturedChunk(repoDir, new LvcCaptureEngine.Result(lossy.manifest().site("main").fullHashes()));
        IntegrationTestSupport.assertEquals(0, emptied.blockEntities().size(), "lossy captured chunk should not retain stale block entity NBT");
    }

    private static void semanticRepositoryCommitUpdatesOnlyChangedChunkReference() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-update-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Update", singleLineSite(17), placementAt(0, 0, 0), reader, player("SemanticUpdate"));

        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main", placementAt(0, 0, 0), reader, player("SemanticUpdate"), "change second chunk");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").fullHashes().get("0,0,0"), update.manifest().site("main").fullHashes().get("0,0,0"), "unchanged full hash should be reused");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").trackedHashes().get("0,0,0"), update.manifest().site("main").trackedHashes().get("0,0,0"), "unchanged tracked hash should be reused");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").fullHashes().get("1,0,0").equals(update.manifest().site("main").fullHashes().get("1,0,0")), "changed full hash should update");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").trackedHashes().get("1,0,0").equals(update.manifest().site("main").trackedHashes().get("1,0,0")), "changed tracked hash should update");
        IntegrationTestSupport.assertEquals(update.manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repoDir).site("main").fullHashes(), "updated manifest should be written to disk");
        IntegrationTestSupport.assertEquals(update.manifest().site("main").trackedHashes(), LvcSemanticRepository.readManifest(repoDir).site("main").trackedHashes(), "updated tracked hashes should be written to disk");
        IntegrationTestSupport.assertEquals(update.commit().getId(), LvcRepository.resolveHead(repoDir), "semantic update commit should move HEAD");
    }

    private static void semanticRepositoryCommitPrunesReplacedObjectFromCurrentHead() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Prune", singleLineSite(17), placementAt(0, 0, 0), reader, player("SemanticPrune"));
        String oldObject = init.manifest().site("main").fullHashes().get("1,0,0");

        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main",
                placementAt(0, 0, 0), reader, player("SemanticPrune"), "change second chunk");
        String newObject = update.manifest().site("main").fullHashes().get("1,0,0");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertTrue(!oldObject.equals(newObject), "changed chunk should get a new full object");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, oldObject)), "old replaced object should be pruned from current working tree");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, newObject)), "new replacement object should remain in current working tree");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            String oldObjectPath = LvcChunkStore.objectRepositoryPath(oldObject);
            IntegrationTestSupport.assertTrue(LvcGitTreeReader.readCommitFile(repository, init.commit(), oldObjectPath) != null, "previous commit should still contain pruned object");
            IntegrationTestSupport.assertEquals(null, LvcGitTreeReader.readCommitFile(repository, update.commit(), oldObjectPath), "current commit should not carry pruned object");
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "pruning should be staged in the semantic commit");
        }
    }

    private static void semanticRepositoryCommitKeepsSharedLiveObjectCandidate() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-shared-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Prune Shared", singleLineSite(32), placementAt(0, 0, 0), reader, player("SemanticPruneShared"));
        String sharedObject = init.manifest().site("main").fullHashes().get("0,0,0");

        IntegrationTestSupport.assertEquals(sharedObject, init.manifest().site("main").fullHashes().get("1,0,0"), "identical storage chunks should share one object");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main",
                placementAt(0, 0, 0), reader, player("SemanticPruneShared"), "change first shared chunk");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertTrue(!sharedObject.equals(update.manifest().site("main").fullHashes().get("0,0,0")), "changed chunk should stop using shared object");
        IntegrationTestSupport.assertEquals(sharedObject, update.manifest().site("main").fullHashes().get("1,0,0"), "unchanged chunk should keep shared object live");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, sharedObject)), "shared object candidate should not be pruned while still live");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "shared-object prune skip should leave repo clean");
        }
    }

    private static void semanticObjectPrunerAbortsBeforeDeletingWhenNewObjectIsMissing() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-missing-");
        String oldObject = objectId("old live object");
        String missingNewObject = objectId("missing new object");
        LvcChunkStore.writeObjectIfMissing(repoDir, oldObject, "old".getBytes(StandardCharsets.UTF_8));
        LvcManifest previous = manifestWithObject("Prune Missing", oldObject);
        LvcManifest resulting = manifestWithObject("Prune Missing", missingNewObject);

        try
        {
            LvcSemanticObjectPruner.pruneChangedObjects(repoDir, previous, resulting, "main");
            throw new AssertionError("pruner should reject missing newly referenced objects");
        }
        catch (java.io.IOException expected)
        {
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("Missing LVC object"), "missing object error should explain prune safety failure");
        }

        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, oldObject)), "old candidate must not be deleted after validation failure");
    }

    private static void semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-areas-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Areas", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticAreas"));
        ObjectId initHead = LvcRepository.resolveHead(repoDir);

        List<LvcManifest.Region> expandedRegions = List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(17, 1, 1)));
        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult expanded = LvcSemanticRepository.updateSiteAreas(repoDir, init.manifest(), "main", placementAt(0, 0, 0), expandedRegions, reader, player("SemanticAreas"), "expand area");

        IntegrationTestSupport.assertNotNull(expanded.commit(), "expanded area should create a commit");
        IntegrationTestSupport.assertTrue(!initHead.equals(expanded.commit().getId()), "expanded area should move HEAD");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.manifest().site("main").regions().get(0).size(), "expanded region should be versioned");
        IntegrationTestSupport.assertEquals(2, expanded.manifest().site("main").fullHashes().size(), "17-block area should reference two LVC chunks");
        IntegrationTestSupport.assertEquals(expanded.manifest().site("main").fullHashes().keySet(), expanded.manifest().site("main").trackedHashes().keySet(), "expanded area should track hashes for all refs");
        String removedAreaObject = expanded.manifest().site("main").fullHashes().get("1,0,0");
        IntegrationTestSupport.assertTrue(!removedAreaObject.equals(expanded.manifest().site("main").fullHashes().get("0,0,0")), "removed chunk object should be unique for prune assertion");

        List<LvcManifest.Region> shrunkRegions = List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1)));
        LvcSemanticRepository.CommitResult shrunk = LvcSemanticRepository.updateSiteAreas(repoDir, expanded.manifest(), "main", placementAt(0, 0, 0), shrunkRegions, reader, player("SemanticAreas"), "shrink area");

        IntegrationTestSupport.assertNotNull(shrunk.commit(), "shrunk area should create a commit");
        IntegrationTestSupport.assertEquals(List.of(1, 1, 1), shrunk.manifest().site("main").regions().get(0).size(), "shrunk region should be versioned");
        IntegrationTestSupport.assertEquals(1, shrunk.manifest().site("main").fullHashes().size(), "full hashes with no tracked positions should leave the manifest");
        IntegrationTestSupport.assertEquals(shrunk.manifest().site("main").fullHashes().keySet(), shrunk.manifest().site("main").trackedHashes().keySet(), "shrunk area should keep hashes aligned to refs");
        IntegrationTestSupport.assertEquals(shrunk.commit().getId(), LvcRepository.resolveHead(repoDir), "semantic area update commit should move HEAD");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, removedAreaObject)), "removed area chunk object should be pruned from current working tree");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            String removedObjectPath = LvcChunkStore.objectRepositoryPath(removedAreaObject);
            IntegrationTestSupport.assertTrue(LvcGitTreeReader.readCommitFile(repository, expanded.commit(), removedObjectPath) != null, "expanded commit should still contain removed area object");
            IntegrationTestSupport.assertEquals(null, LvcGitTreeReader.readCommitFile(repository, shrunk.commit(), removedObjectPath), "shrunk commit should not carry removed area object");
        }
    }
}
