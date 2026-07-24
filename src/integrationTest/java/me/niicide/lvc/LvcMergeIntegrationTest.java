package me.niicide.lvc;

import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.git.LvcBranchMergeOps;
import me.niicide.lvc.git.LvcGitTreeReader;
import me.niicide.lvc.git.LvcGitBranchOps;
import me.niicide.lvc.git.LvcBranchMergeResult;
import me.niicide.lvc.git.LvcBranchMergeConflictResolution;
import me.niicide.lvc.git.LvcBranchMergeStatus;
import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.niicide.lvc.LvcIntegrationFixtures.twoBlockSite;
import static me.niicide.lvc.LvcRepositoryTestSupport.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.git.LvcMergeConflictException;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.task.LvcOperationJournal;

final class LvcMergeIntegrationTest
{
    private LvcMergeIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("merge branch fast-forwards current branch", LvcMergeIntegrationTest::mergeBranchFastForwardsCurrentBranch);
        IntegrationTestSupport.run("merge branch creates semantic two-parent merge commit", LvcMergeIntegrationTest::mergeBranchCreatesSemanticTwoParentMergeCommit);
        IntegrationTestSupport.run("merge branch cancels semantic conflicts without moving head", LvcMergeIntegrationTest::mergeBranchCancelsSemanticConflictsWithoutMovingHead);
        IntegrationTestSupport.run("merge branch conflicts when same inventory changes differently", LvcMergeIntegrationTest::mergeBranchConflictsWhenSameInventoryChangesDifferently);
        IntegrationTestSupport.run("merge branch conflict resolution accepts base incoming and yours", LvcMergeIntegrationTest::mergeBranchConflictResolutionAcceptsBaseIncomingAndYours);
    }

    private static void mergeBranchFastForwardsCurrentBranch() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Fast Forward Merge");
        String branchName = LvcGitBranchOps.createAndCheckoutBranch(repo.path(), "feature/ff");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "FastForwardFeature", "feature work");

        LvcGitBranchOps.checkoutBranchToWorkingTree(repo.path(), LvcGitBranchOps.DEFAULT_BRANCH);

        LvcBranchMergeResult result = LvcBranchMergeOps.mergeBranch(repo.path(), branchName, player("FastForwardMerger"));

        IntegrationTestSupport.assertEquals(LvcBranchMergeStatus.FAST_FORWARD, result.status(), "merge should fast-forward when current branch is an ancestor");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), result.commitId(), "fast-forward result commit");
        IntegrationTestSupport.assertEquals(LvcGitBranchOps.DEFAULT_BRANCH, LvcGitBranchOps.headPointerName(repo.path()), "fast-forward merge should keep current branch checked out");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), LvcRepository.resolveHead(repo.path()), "fast-forward merge should move current branch to source tip");
        assertMergeRestoreJournal(repo.path(), result.commitId(), LvcGitBranchOps.DEFAULT_BRANCH, branchName, repo.second().commit().getName());

        try (Git git = Git.open(repo.path().toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit head = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD));
            IntegrationTestSupport.assertEquals(1, head.getParentCount(), "fast-forward should not create a merge commit");
        }
    }

    private static void mergeBranchCreatesSemanticTwoParentMergeCommit() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult initial = LvcSemanticRepository.initProject(repoDir, "Semantic Merge", twoBlockSite(), placementAt(0, 0, 0), reader, player("MergeInitial"));
        String branchName = LvcGitBranchOps.createAndCheckoutBranch(repoDir, "feature/semantic");

        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "MergeFeature", "feature changes second block");

        LvcGitBranchOps.checkoutBranchToWorkingTree(repoDir, LvcGitBranchOps.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "MergeMain", "main changes first block");
        String mainObject = mainCommit.manifest().site("main").fullHashes().get("0,0,0");

        LvcBranchMergeResult result = LvcBranchMergeOps.mergeBranch(repoDir, branchName, player("SemanticMerger"));

        IntegrationTestSupport.assertEquals(LvcBranchMergeStatus.MERGED, result.status(), "diverged branches should create a merge commit");
        IntegrationTestSupport.assertEquals(1, result.mergedChunks(), "different block edits in the same LVC chunk should merge semantically");
        IntegrationTestSupport.assertEquals(LvcGitBranchOps.DEFAULT_BRANCH, LvcGitBranchOps.headPointerName(repoDir), "merge should keep target branch checked out");
        assertMergeRestoreJournal(repoDir, result.commitId(), LvcGitBranchOps.DEFAULT_BRANCH, branchName, mainCommit.commit().getName());
        String mergedObject = LvcSemanticRepository.readManifest(repoDir).site("main").fullHashes().get("0,0,0");
        String mainObjectPath = LvcChunkStore.objectRepositoryPath(mainObject);

        IntegrationTestSupport.assertTrue(!mainObject.equals(mergedObject), "semantic merge should create a new merged object");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, mainObject)), "merge should prune obsolete target-branch object from current working tree");

        try (Git git = Git.open(repoDir.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            RevCommit head = revWalk.parseCommit(repository.resolve(Constants.HEAD));
            IntegrationTestSupport.assertEquals(2, head.getParentCount(), "semantic merge should create a two-parent commit");
            IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), head.getParent(0).getId(), "first merge parent should be current branch tip");
            IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), head.getParent(1).getId(), "second merge parent should be source branch tip");
            IntegrationTestSupport.assertTrue(LvcGitTreeReader.readCommitFile(repository, mainCommit.commit(), mainObjectPath) != null, "target parent should still contain pruned object");
            IntegrationTestSupport.assertEquals(null, LvcGitTreeReader.readCommitFile(repository, head, mainObjectPath), "merge commit should not carry pruned target object");
        }

        LvcChunk mergedChunk = readOnlyChunk(repoDir);
        IntegrationTestSupport.assertEquals("minecraft:dirt", mergedChunk.blockStateAtTrackedOrdinal(0), "merged chunk should keep main's first block edit");
        IntegrationTestSupport.assertEquals("minecraft:gold_block", mergedChunk.blockStateAtTrackedOrdinal(1), "merged chunk should include feature's second block edit");
    }

    private static void mergeBranchCancelsSemanticConflictsWithoutMovingHead() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-conflict-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Merge Conflict", twoBlockSite(), placementAt(0, 0, 0), reader, player("ConflictInitial"));
        String branchName = LvcGitBranchOps.createAndCheckoutBranch(repoDir, "feature/conflict");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ConflictFeature", "feature changes first block");

        LvcGitBranchOps.checkoutBranchToWorkingTree(repoDir, LvcGitBranchOps.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ConflictMain", "main changes first block");

        try
        {
            LvcBranchMergeOps.mergeBranch(repoDir, branchName, player("ConflictMerger"));
            throw new AssertionError("merge should be canceled when the same tracked block changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(), "same-block semantic conflict should be typed as block payload");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcGitBranchOps.localBranchTipCommitId(repoDir, branchName), "conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcGitBranchOps.hasUncommittedChanges(repoDir), "conflict cancel must leave working tree clean");
        IntegrationTestSupport.assertEquals("minecraft:dirt", readOnlyChunk(repoDir).blockStateAtTrackedOrdinal(0), "conflict cancel should leave target branch content checked out");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "conflict-only merge must not leave a recovery journal");
    }

    private static void mergeBranchConflictsWhenSameInventoryChangesDifferently() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-inventory-conflict-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:furnace");
        LvcIntPosition pos = new LvcIntPosition(0, 0, 0);
        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:stone", "Merge Inventory"));
        LvcSemanticRepository.initProject(repoDir, "Semantic Merge Inventory Conflict",
                singleLineSite(), placementAt(0, 0, 0), reader, player("InventoryConflictInitial"));
        String branchName = LvcGitBranchOps.createAndCheckoutBranch(repoDir, "feature/inventory-conflict");

        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:gold_ingot", "Merge Inventory"));
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "InventoryConflictFeature", "feature changes inventory");

        LvcGitBranchOps.checkoutBranchToWorkingTree(repoDir, LvcGitBranchOps.DEFAULT_BRANCH);
        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:diamond", "Merge Inventory"));
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "InventoryConflictMain", "main changes inventory");

        try
        {
            LvcBranchMergeOps.mergeBranch(repoDir, branchName, player("InventoryConflictMerger"));
            throw new AssertionError("merge should be canceled when the same inventory changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(), "same-inventory semantic conflict should be typed as block payload");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "inventory merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "inventory conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcGitBranchOps.localBranchTipCommitId(repoDir, branchName), "inventory conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcGitBranchOps.hasUncommittedChanges(repoDir), "inventory conflict cancel must leave working tree clean");
        IntegrationTestSupport.assertEquals("minecraft:furnace", readOnlyChunk(repoDir).blockStateAtTrackedOrdinal(0), "inventory conflict cancel should leave target branch content checked out");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "inventory conflict-only merge must not leave a recovery journal");
    }

    private static void mergeBranchConflictResolutionAcceptsBaseIncomingAndYours() throws Exception
    {
        for (LvcBranchMergeConflictResolution resolution : List.of(
                LvcBranchMergeConflictResolution.BASE,
                LvcBranchMergeConflictResolution.INCOMING,
                LvcBranchMergeConflictResolution.YOURS))
        {
            Path repoDir = Files.createTempDirectory("lvc-semantic-merge-resolve-");
            FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
            LvcSemanticRepository.initProject(repoDir, "Semantic Merge Resolve", twoBlockSite(), placementAt(0, 0, 0), reader, player("ResolveInitial" + resolution));
            String branchName = LvcGitBranchOps.createAndCheckoutBranch(repoDir, "feature/resolve-" + resolution.name().toLowerCase(java.util.Locale.ROOT));

            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:emerald_block");
            LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ResolveFeature" + resolution, "feature changes conflict and second block");

            LvcGitBranchOps.checkoutBranchToWorkingTree(repoDir, LvcGitBranchOps.DEFAULT_BRANCH);
            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
            LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ResolveMain" + resolution, "main changes conflicting block");

            LvcBranchMergeResult result = LvcBranchMergeOps.mergeBranch(repoDir, branchName, player("ResolveMerger" + resolution), resolution);

            IntegrationTestSupport.assertEquals(LvcBranchMergeStatus.MERGED, result.status(), "resolved conflict should create a merge commit");

            try (Git git = Git.open(repoDir.toFile());
                 RevWalk revWalk = new RevWalk(git.getRepository()))
            {
                RevCommit head = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(2, head.getParentCount(), "resolved conflict merge should have two parents");
                IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), head.getParent(0).getId(), "resolved merge first parent");
                IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), head.getParent(1).getId(), "resolved merge second parent");
            }

            LvcChunk mergedChunk = readOnlyChunk(repoDir);
            IntegrationTestSupport.assertEquals(expectedResolvedConflictState(resolution), mergedChunk.blockStateAtTrackedOrdinal(0), "resolved conflict should choose requested side");
            IntegrationTestSupport.assertEquals("minecraft:emerald_block", mergedChunk.blockStateAtTrackedOrdinal(1), "resolved conflict should still keep non-conflicting incoming edit");
            IntegrationTestSupport.assertTrue(!LvcGitBranchOps.hasUncommittedChanges(repoDir), "resolved conflict merge should leave working tree clean");
        }
    }
}
