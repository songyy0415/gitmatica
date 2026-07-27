package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.niicide.lvc.LvcIntegrationFixtures.twoBlockSite;
import static me.niicide.lvc.LvcIntegrationFixtures.twoNamedBlockRegions;
import static me.niicide.lvc.LvcRepositoryTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.git.LvcMergeConflictException;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;
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
        IntegrationTestSupport.run("merge branch combines independent sub-region renames", LvcMergeIntegrationTest::mergeBranchCombinesIndependentSubRegionRenames);
        IntegrationTestSupport.run("merge branch combines independent sub-region bounds changes", LvcMergeIntegrationTest::mergeBranchCombinesIndependentSubRegionBoundsChanges);
        IntegrationTestSupport.run("merge branch merges blocks within expanded tracking area", LvcMergeIntegrationTest::mergeBranchMergesBlocksWithinExpandedTrackingArea);
        IntegrationTestSupport.run("merge branch conflicts when same sub-region bounds change differently", LvcMergeIntegrationTest::mergeBranchConflictsWhenSameSubRegionBoundsChangeDifferently);
        IntegrationTestSupport.run("structural conflict choice still merges block payloads", LvcMergeIntegrationTest::structuralConflictChoiceStillMergesBlockPayloads);
        IntegrationTestSupport.run("merge branch applies one source to every block conflict", LvcMergeIntegrationTest::mergeBranchAppliesOneSourceToEveryBlockConflict);
    }

    private static void mergeBranchFastForwardsCurrentBranch() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Fast Forward Merge");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/ff");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "FastForwardFeature", "feature work");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repo.path(), branchName, player("FastForwardMerger"));

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.FAST_FORWARD, result.status(), "merge should fast-forward when current branch is an ancestor");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), result.commitId(), "fast-forward result commit");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "fast-forward merge should keep current branch checked out");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getId(), LvcRepository.resolveHead(repo.path()), "fast-forward merge should move current branch to source tip");
        assertMergeRestoreJournal(repo.path(), result.commitId(), LvcProjectService.DEFAULT_BRANCH, branchName, repo.second().commit().getName());

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
        LvcSemanticRepository.CommitResult initial = LvcSemanticRepository.initProject(repoDir, "Semantic Merge", twoNamedBlockRegions(), placementAt(0, 0, 0), reader, player("MergeInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/semantic");

        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "MergeFeature", "feature changes second block");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "MergeMain", "main changes first block");
        String mainObject = mainCommit.manifest().site("main").fullHashes().get("0,0,0");

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repoDir, branchName, player("SemanticMerger"));

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(), "diverged branches should create a merge commit");
        IntegrationTestSupport.assertEquals(1, result.mergedChunks(), "different block edits in the same LVC chunk should merge semantically");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repoDir), "merge should keep target branch checked out");
        assertMergeRestoreJournal(repoDir, result.commitId(), LvcProjectService.DEFAULT_BRANCH, branchName, mainCommit.commit().getName());
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
            IntegrationTestSupport.assertTrue(LvcProjectGitOps.readCommitFile(repository, mainCommit.commit(), mainObjectPath) != null, "target parent should still contain pruned object");
            IntegrationTestSupport.assertEquals(null, LvcProjectGitOps.readCommitFile(repository, head, mainObjectPath), "merge commit should not carry pruned target object");
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
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/conflict");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ConflictFeature", "feature changes first block");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ConflictMain", "main changes first block");

        try
        {
            LvcProjectService.mergeBranch(repoDir, branchName, player("ConflictMerger"));
            throw new AssertionError("merge should be canceled when the same tracked block changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(),
                    "block conflicts should remain block-payload conflicts after definitions merge");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repoDir, branchName), "conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "conflict cancel must leave working tree clean");
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
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/inventory-conflict");

        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:gold_ingot", "Merge Inventory"));
        LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "InventoryConflictFeature", "feature changes inventory");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlockEntity(pos, inventoryBlockEntity("minecraft:diamond", "Merge Inventory"));
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "InventoryConflictMain", "main changes inventory");

        try
        {
            LvcProjectService.mergeBranch(repoDir, branchName, player("InventoryConflictMerger"));
            throw new AssertionError("merge should be canceled when the same inventory changed differently");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.BLOCK_PAYLOAD, expected.reason(),
                    "inventory conflicts should remain block-payload conflicts after definitions merge");
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("conflict"), "inventory merge conflict error should explain conflict context");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "inventory conflict cancel must not move HEAD");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repoDir, branchName), "inventory conflict cancel must not move source branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "inventory conflict cancel must leave working tree clean");
        IntegrationTestSupport.assertEquals("minecraft:furnace", readOnlyChunk(repoDir).blockStateAtTrackedOrdinal(0), "inventory conflict cancel should leave target branch content checked out");
        IntegrationTestSupport.assertEquals(null, LvcOperationJournal.read(repoDir), "inventory conflict-only merge must not leave a recovery journal");
    }

    private static void mergeBranchConflictResolutionAcceptsBaseIncomingAndYours() throws Exception
    {
        for (LvcProjectService.BranchMergeConflictResolution resolution : List.of(
                LvcProjectService.BranchMergeConflictResolution.BASE,
                LvcProjectService.BranchMergeConflictResolution.INCOMING,
                LvcProjectService.BranchMergeConflictResolution.YOURS))
        {
            Path repoDir = Files.createTempDirectory("lvc-semantic-merge-resolve-");
            FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
            LvcSemanticRepository.initProject(repoDir, "Semantic Merge Resolve", twoBlockSite(), placementAt(0, 0, 0), reader, player("ResolveInitial" + resolution));
            String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/resolve-" + resolution.name().toLowerCase(java.util.Locale.ROOT));

            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:emerald_block");
            LvcSemanticRepository.CommitResult featureCommit = commitCurrent(repoDir, reader, "ResolveFeature" + resolution, "feature changes conflict and second block");

            LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
            reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
            reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:stone");
            LvcSemanticRepository.CommitResult mainCommit = commitCurrent(repoDir, reader, "ResolveMain" + resolution, "main changes conflicting block");

            LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(repoDir, branchName, player("ResolveMerger" + resolution), resolution);

            IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(), "resolved conflict should create a merge commit");

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
            IntegrationTestSupport.assertEquals("minecraft:emerald_block",
                    mergedChunk.blockStateAtTrackedOrdinal(1),
                    "non-conflicting blocks should still merge independently inside a conflicted sub-region");
            IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "resolved conflict merge should leave working tree clean");
        }
    }

    private static void mergeBranchCombinesIndependentSubRegionRenames() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-region-renames-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Region Renames", twoNamedBlockRegions(),
                placementAt(0, 0, 0), reader, player("RegionRenameInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/rename-right");

        renameRegionInWorkingManifest(repoDir, "Right", "Feature Right");
        commitCurrent(repoDir, reader, "RegionRenameFeature", "rename right");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        renameRegionInWorkingManifest(repoDir, "Left", "Current Left");
        commitCurrent(repoDir, reader, "RegionRenameMain", "rename left");

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                repoDir, branchName, player("RegionRenameMerger"));
        List<String> names = LvcSemanticRepository.readManifest(repoDir).site("main").regions().stream()
                .map(LvcManifest.Region::name)
                .toList();

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(),
                "independent sub-region renames should merge");
        IntegrationTestSupport.assertEquals(List.of("Current Left", "Feature Right"), names,
                "renaming uses delete-plus-add identity and preserves independent renamed regions");
    }

    private static void mergeBranchCombinesIndependentSubRegionBoundsChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-region-bounds-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Region Bounds", twoNamedBlockRegions(),
                placementAt(0, 0, 0), reader, player("RegionBoundsInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/move-right");

        moveRegionInWorkingManifest(repoDir, "Right", List.of(11, 0, 0));
        commitCurrent(repoDir, reader, "RegionBoundsFeature", "move right");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        moveRegionInWorkingManifest(repoDir, "Left", List.of(10, 0, 0));
        commitCurrent(repoDir, reader, "RegionBoundsMain", "move left");

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                repoDir, branchName, player("RegionBoundsMerger"));
        List<LvcManifest.Region> regions = LvcSemanticRepository.readManifest(repoDir)
                .site("main")
                .regions();

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(),
                "independent sub-region bounds changes should merge");
        IntegrationTestSupport.assertEquals(List.of(10, 0, 0), regions.get(0).min(),
                "merge should keep the current branch bounds change");
        IntegrationTestSupport.assertEquals(List.of(11, 0, 0), regions.get(1).min(),
                "merge should include the incoming branch bounds change");
    }

    private static void mergeBranchMergesBlocksWithinExpandedTrackingArea() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-expanded-region-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Expanded Region", singleLineSite(),
                placementAt(0, 0, 0), reader, player("ExpandedRegionInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/expand-line");

        resizeRegionInWorkingManifest(repoDir, "Line", List.of(2, 1, 1));
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:gold_block");
        commitCurrent(repoDir, reader, "ExpandedRegionFeature", "expand line and add block");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        commitCurrent(repoDir, reader, "ExpandedRegionMain", "change original block");

        LvcProjectService.BranchMergeResult result = LvcProjectService.mergeBranch(
                repoDir, branchName, player("ExpandedRegionMerger"));
        LvcManifest.Site mergedSite = LvcSemanticRepository.readManifest(repoDir).site("main");
        LvcChunk mergedChunk = readOnlyChunk(repoDir);

        IntegrationTestSupport.assertEquals(LvcProjectService.BranchMergeStatus.MERGED, result.status(),
                "expanded sub-region and independent block change should merge");
        IntegrationTestSupport.assertEquals(List.of(2, 1, 1), mergedSite.regions().get(0).size(),
                "structural merge should establish the expanded tracking area first");
        IntegrationTestSupport.assertEquals("minecraft:dirt",
                mergedChunk.blockStateAtTrackedOrdinal(0),
                "block merge should retain the current change inside the original area");
        IntegrationTestSupport.assertEquals("minecraft:gold_block",
                mergedChunk.blockStateAtTrackedOrdinal(1),
                "block merge should include incoming content from the newly tracked area");
    }

    private static void mergeBranchConflictsWhenSameSubRegionBoundsChangeDifferently() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-region-bounds-conflict-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Region Bounds Conflict", twoNamedBlockRegions(),
                placementAt(0, 0, 0), reader, player("RegionBoundsConflictInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/move-right-conflict");

        moveRegionInWorkingManifest(repoDir, "Right", List.of(2, 0, 0));
        commitCurrent(repoDir, reader, "RegionBoundsConflictFeature", "move right to two");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        moveRegionInWorkingManifest(repoDir, "Right", List.of(3, 0, 0));
        LvcSemanticRepository.CommitResult mainCommit = commitCurrent(
                repoDir, reader, "RegionBoundsConflictMain", "move right to three");

        try
        {
            LvcProjectService.mergeBranch(repoDir, branchName, player("RegionBoundsConflictMerger"));
            throw new AssertionError("different bounds changes to the same sub-region should conflict");
        }
        catch (LvcMergeConflictException expected)
        {
            IntegrationTestSupport.assertEquals(LvcMergeConflictException.Reason.SUBREGION, expected.reason(),
                    "same-name bounds conflicts should be typed as sub-region conflicts");
        }

        IntegrationTestSupport.assertEquals(mainCommit.commit().getId(), LvcRepository.resolveHead(repoDir),
                "bounds conflict cancel must not move HEAD");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir),
                "bounds conflict cancel must leave the working tree clean");
    }

    private static void structuralConflictChoiceStillMergesBlockPayloads() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-structural-choice-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic Structural Choice", singleLineSite(),
                placementAt(0, 0, 0), reader, player("StructuralChoiceInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/expand-line-to-two");

        resizeRegionInWorkingManifest(repoDir, "Line", List.of(2, 1, 1));
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:gold_block");
        commitCurrent(repoDir, reader, "StructuralChoiceFeature", "expand line to two");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        resizeRegionInWorkingManifest(repoDir, "Line", List.of(3, 1, 1));
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        commitCurrent(repoDir, reader, "StructuralChoiceMain", "expand line to three and change first block");

        LvcProjectService.mergeBranch(
                repoDir,
                branchName,
                player("StructuralChoiceMerger"),
                LvcProjectService.BranchMergeConflictResolution.INCOMING
        );
        LvcManifest.Site mergedSite = LvcSemanticRepository.readManifest(repoDir).site("main");
        LvcChunk mergedChunk = readOnlyChunk(repoDir);

        IntegrationTestSupport.assertEquals(List.of(2, 1, 1),
                mergedSite.regions().get(0).size(),
                "incoming should select only the conflicting sub-region definition");
        IntegrationTestSupport.assertEquals("minecraft:dirt",
                mergedChunk.blockStateAtTrackedOrdinal(0),
                "definition resolution must not replace non-conflicting current block changes");
        IntegrationTestSupport.assertEquals("minecraft:gold_block",
                mergedChunk.blockStateAtTrackedOrdinal(1),
                "definition resolution should retain incoming content in the expanded area");
    }

    private static void mergeBranchAppliesOneSourceToEveryBlockConflict() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-merge-all-region-conflicts-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.initProject(repoDir, "Semantic All Region Conflicts", twoNamedBlockRegions(),
                placementAt(0, 0, 0), reader, player("AllConflictInitial"));
        String branchName = LvcProjectService.createAndCheckoutBranch(repoDir, "feature/all-conflicts");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:emerald_block");
        commitCurrent(repoDir, reader, "AllConflictFeature", "incoming changes both regions");

        LvcProjectService.checkoutBranchToWorkingTree(repoDir, LvcProjectService.DEFAULT_BRANCH);
        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:diamond_block");
        commitCurrent(repoDir, reader, "AllConflictMain", "current changes both regions");

        LvcProjectService.mergeBranch(repoDir, branchName, player("AllConflictMerger"),
                LvcProjectService.BranchMergeConflictResolution.INCOMING);
        LvcChunk mergedChunk = readOnlyChunk(repoDir);

        IntegrationTestSupport.assertEquals("minecraft:gold_block",
                mergedChunk.blockStateAtTrackedOrdinal(0),
                "incoming choice should apply to the first conflicted sub-region");
        IntegrationTestSupport.assertEquals("minecraft:emerald_block",
                mergedChunk.blockStateAtTrackedOrdinal(1),
                "incoming choice should apply to every conflicted sub-region");
    }

    private static void renameRegionInWorkingManifest(Path repoDir, String oldName, String newName) throws Exception
    {
        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);
        LvcManifest.Site site = manifest.site("main");
        List<LvcManifest.Region> regions = site.regions().stream()
                .map(region -> region.name().equals(oldName) ?
                        new LvcManifest.Region(newName, region.min(), region.size()) :
                        region)
                .toList();
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir,
                manifest.withSite("main", site.withRegions(regions)));
    }

    private static void moveRegionInWorkingManifest(Path repoDir, String name, List<Integer> min) throws Exception
    {
        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);
        LvcManifest.Site site = manifest.site("main");
        List<LvcManifest.Region> regions = site.regions().stream()
                .map(region -> region.name().equals(name) ?
                        new LvcManifest.Region(region.name(), min, region.size()) :
                        region)
                .toList();
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir,
                manifest.withSite("main", site.withRegions(regions)));
    }

    private static void resizeRegionInWorkingManifest(Path repoDir, String name, List<Integer> size) throws Exception
    {
        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);
        LvcManifest.Site site = manifest.site("main");
        List<LvcManifest.Region> regions = site.regions().stream()
                .map(region -> region.name().equals(name) ?
                        new LvcManifest.Region(region.name(), region.min(), size) :
                        region)
                .toList();
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir,
                manifest.withSite("main", site.withRegions(regions)));
    }
}
