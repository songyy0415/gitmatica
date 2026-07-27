package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcRepositoryTestSupport.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcCheckoutUndoIntegrationTest
{
    private LvcCheckoutUndoIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("checkout updates the semantic working tree while preserving visible history", LvcCheckoutUndoIntegrationTest::checkoutUpdatesSemanticWorkingTreeWhilePreservingVisibleHistory);
        IntegrationTestSupport.run("same tip branch checkout preserves dirty tracked files", LvcCheckoutUndoIntegrationTest::sameTipBranchCheckoutPreservesDirtyTrackedFiles);
        IntegrationTestSupport.run("commit after checkout is rejected while HEAD is detached", LvcCheckoutUndoIntegrationTest::commitAfterCheckoutIsRejectedWhileHeadIsDetached);
        IntegrationTestSupport.run("reset working tree to HEAD discards tracked dirty changes", LvcCheckoutUndoIntegrationTest::resetWorkingTreeToHeadDiscardsTrackedDirtyChanges);
        IntegrationTestSupport.run("undo latest commit keep changes preserves dirty working tree", LvcCheckoutUndoIntegrationTest::undoLatestCommitKeepChangesPreservesDirtyWorkingTree);
        IntegrationTestSupport.run("save version after keep changes undo recommits dirty working tree", LvcCheckoutUndoIntegrationTest::saveVersionAfterKeepChangesUndoRecommitsDirtyWorkingTree);
        IntegrationTestSupport.run("undo latest commit delete changes hard resets to parent", LvcCheckoutUndoIntegrationTest::undoLatestCommitDeleteChangesHardResetsToParent);
        IntegrationTestSupport.run("checkout branch and reset to commit moves branch tip", LvcCheckoutUndoIntegrationTest::checkoutBranchAndResetToCommitMovesBranchTip);
        IntegrationTestSupport.run("checkout can continue after resetting a dirty working tree", LvcCheckoutUndoIntegrationTest::checkoutCanContinueAfterResettingDirtyWorkingTree);
    }

    private static void checkoutUpdatesSemanticWorkingTreeWhilePreservingVisibleHistory() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Checkout Project");
        List<LvcProjectService.CommitInfo> branchHistoryBeforeCheckout = LvcProjectService.listCommits(repo.path());

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(), "checkout should restore lvc.json from the selected commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "checkout should move HEAD to the selected commit");

        List<LvcProjectService.CommitInfo> history = LvcProjectService.listCommits(repo.path());
        IntegrationTestSupport.assertEquals(branchHistoryBeforeCheckout.stream().map(LvcProjectService.CommitInfo::id).toList(), history.stream().map(LvcProjectService.CommitInfo::id).toList(), "checkout should not reorder or replace branch commit history");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(repo.first().commit().getName())), "history should still show checked-out commit");
        IntegrationTestSupport.assertTrue(history.stream().anyMatch(commit -> commit.id().equals(repo.second().commit().getName())), "history should still show branch commits after detached checkout");
    }

    private static void sameTipBranchCheckoutPreservesDirtyTrackedFiles() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Same Tip Branch Switch");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/same-tip");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);
        Files.writeString(manifest, "dirty local edit\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "dirty tracked file should be reported before same-tip branch switch");
        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch tip lookup should resolve the branch commit");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), branchName);

        IntegrationTestSupport.assertEquals(branchName, LvcProjectService.headPointerName(repo.path()), "same-tip checkout should attach HEAD to target branch");
        IntegrationTestSupport.assertTrue(Files.readString(manifest, StandardCharsets.UTF_8).contains("dirty local edit"), "same-tip checkout should keep dirty tracked file content");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "dirty tracked file should stay dirty after same-tip branch switch");
        assertBranchTipRejected(repo.path(), "feature/missing", "Branch does not exist");
    }

    private static void commitAfterCheckoutIsRejectedWhileHeadIsDetached() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Checkout Commit Project");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());
        repo.reader().setBlock(new LvcIntPosition(0, 0, 0), "minecraft:gold_block");

        try
        {
            LvcSemanticRepository.commitSite(repo.path(), repo.first().manifest(), "main", placementAt(0, 0, 0), repo.reader(), player("DetachedCommit"), "after checkout");
            throw new AssertionError("commit should fail while HEAD is detached");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("HEAD is detached"), "detached HEAD commit error should explain the reason");
        }

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsed = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(repo.first().commit().getId(), parsed.getId(), "detached HEAD should stay on the checked-out commit after rejected commit");
            }
        }

        List<String> historyAfterRejectedCommit = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName(), repo.first().commit().getName()), historyAfterRejectedCommit, "branch history should stay on main after rejected detached commit");
    }

    private static void resetWorkingTreeToHeadDiscardsTrackedDirtyChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-dirty-reset-");
        LvcSemanticRepository.CommitResult commit = createInitialCommit(repoDir, "Dirty Reset", new FakeWorldReader("minecraft:stone"), "DirtyReset");
        Path manifest = repoDir.resolve(LvcSemanticRepository.MANIFEST);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        LvcManifest originalSemanticManifest = LvcSemanticRepository.readManifest(repoDir);
        Path untracked = repoDir.resolve("local-notes.txt");

        LvcManifest.Site originalSite = originalSemanticManifest.site("main");
        LvcManifest.Region originalRegion = originalSite.regions().get(0);
        LvcManifest.Region editedRegion = new LvcManifest.Region(
                "Locally Renamed",
                List.of(4, 5, 6),
                List.of(7, 8, 9)
        );
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir,
                originalSemanticManifest.withSite("main", originalSite.withRegions(List.of(editedRegion))));
        Files.writeString(untracked, "untracked local notes\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repoDir),
                "sub-region edits should be reported before discard reset");

        LvcProjectService.resetWorkingTreeToHead(repoDir);

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repoDir), "reset should clean tracked local edits");
        IntegrationTestSupport.assertEquals(commit.commit().getId(), LvcRepository.resolveHead(repoDir), "reset should leave HEAD at the same commit");
        IntegrationTestSupport.assertEquals(originalManifest, Files.readString(manifest, StandardCharsets.UTF_8), "manifest should be restored from HEAD");
        IntegrationTestSupport.assertEquals(originalSite.regions(),
                LvcSemanticRepository.readManifest(repoDir).site("main").regions(),
                "discard reset should restore every sub-region definition from HEAD");
        IntegrationTestSupport.assertTrue(Files.exists(untracked), "reset to HEAD should leave untracked files alone");
    }

    private static void undoLatestCommitKeepChangesPreservesDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoKeep");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);
        String latestManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        Files.writeString(manifest, latestManifest + "\n ", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "local manifest whitespace edit should be dirty before undo");

        LvcProjectService.LatestCommitUndoResult result = LvcProjectService.undoLatestCommitKeepChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), result.commitId(), "keep-changes undo should report deleted commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getName(), result.parentCommitId(), "keep-changes undo should report parent commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "keep-changes undo should move HEAD to parent");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "keep-changes undo should leave working tree dirty");
        IntegrationTestSupport.assertEquals(repo.second().manifest().site("main").fullHashes(),
                LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(),
                "keep-changes undo should keep the deleted commit content in the working tree");
    }

    private static void saveVersionAfterKeepChangesUndoRecommitsDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoKeepRecommit");

        LvcProjectService.undoLatestCommitKeepChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "keep-changes undo should move HEAD to parent before recommit");
        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "keep-changes undo should leave semantic files dirty before recommit");

        LvcSemanticRepository.CommitResult recommit = LvcSemanticRepository.commitSite(
                repo.path(),
                LvcSemanticRepository.readManifest(repo.path()),
                "main",
                placementAt(0, 0, 0),
                repo.reader(),
                player("UndoKeepRecommit"),
                "recommit kept changes"
        );

        IntegrationTestSupport.assertNotNull(recommit.commit(), "saving kept changes should create a new commit");
        IntegrationTestSupport.assertEquals(repo.second().manifest().site("main").fullHashes(), recommit.manifest().site("main").fullHashes(),
                "recommit should preserve kept semantic content");
        IntegrationTestSupport.assertEquals(recommit.commit().getId(), LvcRepository.resolveHead(repo.path()), "recommit should become HEAD");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "recommit should clean the semantic working tree");

        try (Git git = Git.open(repo.path().toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit parsed = revWalk.parseCommit(recommit.commit().getId());
            IntegrationTestSupport.assertEquals(1, parsed.getParentCount(), "recommit should have one parent");
            IntegrationTestSupport.assertEquals(repo.first().commit().getId(), parsed.getParent(0).getId(), "recommit should parent the kept-changes base commit");
        }
    }

    private static void undoLatestCommitDeleteChangesHardResetsToParent() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("UndoDelete");
        Path manifest = repo.path().resolve(LvcSemanticRepository.MANIFEST);
        String latestManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        Files.writeString(manifest, latestManifest + "\n ", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "local manifest whitespace edit should be dirty before delete undo");

        LvcProjectService.LatestCommitUndoResult result = LvcProjectService.undoLatestCommitDeleteChanges(repo.path());

        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), result.commitId(), "delete-changes undo should report deleted commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getName(), result.parentCommitId(), "delete-changes undo should report parent commit");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "delete-changes undo should move HEAD to parent");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "delete-changes undo should leave working tree clean");
        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(),
                LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(),
                "delete-changes undo should restore the parent commit content");
    }

    private static void checkoutBranchAndResetToCommitMovesBranchTip() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("BranchReset");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/reset");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(),
                "minecraft:gold_block", "BranchResetFeature", "feature work");

        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch should start at feature commit");

        LvcProjectService.checkoutBranchAndResetToCommit(repo.path(), branchName, repo.second().commit().getName());

        IntegrationTestSupport.assertEquals(branchName, LvcProjectService.headPointerName(repo.path()), "reset branch should be checked out");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reset branch should move HEAD to rollback commit");
        IntegrationTestSupport.assertEquals(repo.second().commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), branchName), "branch tip should move to rollback commit");
        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "branch reset should leave working tree clean");
    }

    private static void checkoutCanContinueAfterResettingDirtyWorkingTree() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reset Checkout");

        Files.writeString(repo.path().resolve(LvcSemanticRepository.MANIFEST), "local edits\n", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(LvcProjectService.hasUncommittedChanges(repo.path()), "tracked local edits should be reported before checkout reset");

        LvcProjectService.resetWorkingTreeToHead(repo.path());
        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasUncommittedChanges(repo.path()), "checkout after reset should leave the working tree clean");
        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "checkout after reset should move HEAD to the requested commit");
        IntegrationTestSupport.assertTrue(!repo.second().commit().getId().equals(LvcRepository.resolveHead(repo.path())), "checkout after reset should not remain on the previous commit");
        IntegrationTestSupport.assertEquals(repo.first().manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repo.path()).site("main").fullHashes(), "checkout after reset should restore selected semantic manifest");
    }
}
