package me.niicide.lvc;

import static me.niicide.lvc.LvcRepositoryTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcBranchIntegrationTest
{
    private LvcBranchIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("commit history remains scoped to the branch after checkout", LvcBranchIntegrationTest::commitHistoryRemainsScopedToBranchAfterCheckout);
        IntegrationTestSupport.run("head commit detection compares resolved Git commits", LvcBranchIntegrationTest::headCommitDetectionComparesResolvedGitCommits);
        IntegrationTestSupport.run("head pointer name shows branch or detached commit", LvcBranchIntegrationTest::headPointerNameShowsBranchOrDetachedCommit);
        IntegrationTestSupport.run("detached head at branch tip can reattach without restore", LvcBranchIntegrationTest::detachedHeadAtBranchTipCanReattachWithoutRestore);
        IntegrationTestSupport.run("detached older head can reattach after checking out branch tip", LvcBranchIntegrationTest::detachedOlderHeadCanReattachAfterCheckingOutBranchTip);
        IntegrationTestSupport.run("create branch shows branch start and later commits", LvcBranchIntegrationTest::createBranchShowsBranchStartAndLaterCommits);
        IntegrationTestSupport.run("create branch rejects duplicate invalid and empty repo inputs", LvcBranchIntegrationTest::createBranchRejectsDuplicateInvalidAndEmptyRepoInputs);
        IntegrationTestSupport.run("delete branch removes non-current branch and rejects protected inputs", LvcBranchIntegrationTest::deleteBranchRemovesNonCurrentBranchAndRejectsProtectedInputs);
        IntegrationTestSupport.run("rename branch updates refs metadata and rejects invalid inputs", LvcBranchIntegrationTest::renameBranchUpdatesRefsMetadataAndRejectsInvalidInputs);
    }

    private static void commitHistoryRemainsScopedToBranchAfterCheckout() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Branch Scoped History");
        List<String> branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        List<String> historyAfterDetachedCommit = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(branchHistory, historyAfterDetachedCommit, "history should stay scoped to the branch selected before checkout");
        IntegrationTestSupport.assertTrue(historyAfterDetachedCommit.contains(repo.second().commit().getName()), "branch tip should remain visible");
    }

    private static void headCommitDetectionComparesResolvedGitCommits() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Head Match");

        IntegrationTestSupport.assertTrue(LvcProjectService.headMatchesCommit(repo.path(), repo.second().commit().getName()), "branch HEAD should match the newest commit");
        IntegrationTestSupport.assertTrue(!LvcProjectService.headMatchesCommit(repo.path(), repo.first().commit().getName()), "branch HEAD should not match an older commit");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(LvcProjectService.headMatchesCommit(repo.path(), repo.first().commit().getName().substring(0, 8)), "detached HEAD should match the selected short commit id");
    }

    private static void headPointerNameShowsBranchOrDetachedCommit() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Head Pointer");

        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "attached HEAD should display the branch name");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertEquals(repo.first().commit().getName().substring(0, 8), LvcProjectService.headPointerName(repo.path()), "detached HEAD should display the short commit id");
    }

    private static void detachedHeadAtBranchTipCanReattachWithoutRestore() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reattach Tip");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.second().commit().getName());

        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "checking out the current commit by id should detach HEAD");
        IntegrationTestSupport.assertTrue(LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "detached branch tip should reattach to the branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "reattached HEAD should be on a local branch");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reattach must not move HEAD");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        IntegrationTestSupport.assertTrue(!LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "older detached commits should not silently reattach to the branch tip");
        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "older checkout should remain detached");
    }

    private static void detachedOlderHeadCanReattachAfterCheckingOutBranchTip() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Reattach After Tip Checkout");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());
        IntegrationTestSupport.assertTrue(LvcProjectService.isDetachedHead(repo.path()), "older checkout should detach HEAD");

        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.second().commit().getName());
        IntegrationTestSupport.assertTrue(LvcProjectService.reattachHeadToBranchIfAtTip(repo.path(), LvcProjectService.DEFAULT_BRANCH), "checking out the branch tip by commit id should be reattachable");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "branch tip checkout should end attached after reattach");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "reattach after tip checkout must keep HEAD at the selected commit");
    }

    private static void createBranchShowsBranchStartAndLaterCommits() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Create Branch History");
        List<String> mainHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/build");

        IntegrationTestSupport.assertEquals("feature/build", branchName, "created branch name should be normalized");
        IntegrationTestSupport.assertEquals("feature/build", LvcProjectService.headPointerName(repo.path()), "new branch should become the active HEAD branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.isDetachedHead(repo.path()), "created branch checkout should keep HEAD attached");
        IntegrationTestSupport.assertEquals(repo.second().commit().getId(), LvcRepository.resolveHead(repo.path()), "creating a branch must not move away from the current commit");

        List<String> branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName()), branchHistory, "new branch should show only its start commit before new work");

        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "FeatureCommit", "feature work");

        branchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(featureCommit.commit().getName(), repo.second().commit().getName()), branchHistory, "branch history should show branch commits back to the start commit");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        List<String> mainHistoryAfterBranchWork = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(mainHistory, mainHistoryAfterBranchWork, "main history should remain full and unchanged");
        LvcProjectService.ProjectSummary summary = LvcProjectService.projectSummary(new LvcProjectService.Project("Create Branch History", repo.path()));
        IntegrationTestSupport.assertEquals(3, summary.versionCount(), "project summary should count unique commits across all local branches");

        List<String> branches = LvcProjectService.listLocalBranches(repo.path());
        IntegrationTestSupport.assertTrue(branches.contains(LvcProjectService.DEFAULT_BRANCH), "main branch should still exist");
        IntegrationTestSupport.assertTrue(branches.contains("feature/build"), "new branch should be listed");
    }

    private static void createBranchRejectsDuplicateInvalidAndEmptyRepoInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Create Branch Validation");

        LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/valid");
        assertCreateBranchRejected(repo.path(), "feature/valid", "Branch already exists");
        assertCreateBranchRejected(repo.path(), "FEATURE/VALID", "Branch already exists");
        assertCreateBranchRejected(repo.path(), " ", "must not be blank");
        assertCreateBranchRejected(repo.path(), "HEAD", "cannot be HEAD");
        assertCreateBranchRejected(repo.path(), "refs/heads/bad", "must not start with refs/");
        assertCreateBranchRejected(repo.path(), "bad branch", "must not contain whitespace");
        assertCreateBranchRejected(repo.path(), "bad..branch", "Invalid branch name");

        Path emptyRepo = Files.createTempDirectory("lvc-empty-branch-");

        try (Git ignored = Git.init().setDirectory(emptyRepo.toFile()).setInitialBranch(LvcProjectService.DEFAULT_BRANCH).call())
        {
            assertCreateBranchRejected(emptyRepo, "feature/empty", "Create the first version");
        }
    }

    private static void deleteBranchRemovesNonCurrentBranchAndRejectsProtectedInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Delete Branch Validation");
        String branchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/delete");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "DeleteFeature", "feature work");

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        String deletedBranchName = LvcProjectService.deleteBranch(repo.path(), branchName);

        IntegrationTestSupport.assertEquals(branchName, deletedBranchName, "deleted branch name should be returned");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains(branchName), "deleted branch should disappear from local branch list");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "deleting another branch must not move HEAD");

        List<String> mainHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();
        IntegrationTestSupport.assertEquals(List.of(repo.second().commit().getName(), repo.first().commit().getName()), mainHistory, "main history should remain unchanged after deleting another branch");

        try (Git git = Git.open(repo.path().toFile()))
        {
            IntegrationTestSupport.assertEquals(null, git.getRepository().getConfig().getString("lvcBranchStart", branchName, "commit"), "branch start metadata should be removed");
            IntegrationTestSupport.assertEquals(null, git.getRepository().resolve(Constants.R_HEADS + branchName), "deleted branch ref should be gone");
            IntegrationTestSupport.assertTrue(git.getRepository().resolve(featureCommit.commit().getName()) != null, "force-deleted branch commit object may remain until Git GC");
        }

        assertDeleteBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "Cannot delete the checked-out branch");
        assertDeleteBranchRejected(repo.path(), "feature/missing", "Branch does not exist");
        assertDeleteBranchRejected(repo.path(), " ", "must not be blank");
    }

    private static void renameBranchUpdatesRefsMetadataAndRejectsInvalidInputs() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Rename Branch Validation");
        String oldBranchName = LvcProjectService.createAndCheckoutBranch(repo.path(), "feature/old");
        LvcSemanticRepository.CommitResult featureCommit = commitSingleBlock(repo.path(), repo.second(), repo.reader(), "minecraft:gold_block", "RenameFeature", "feature work");
        List<String> oldBranchHistory = LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList();

        String renamedCurrentBranch = LvcProjectService.renameBranch(repo.path(), oldBranchName, "feature/new");

        IntegrationTestSupport.assertEquals("feature/new", renamedCurrentBranch, "renamed current branch name should be returned");
        IntegrationTestSupport.assertEquals("feature/new", LvcProjectService.headPointerName(repo.path()), "renaming the checked-out branch should keep HEAD attached to the new branch");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains(oldBranchName), "old branch name should disappear after current branch rename");
        IntegrationTestSupport.assertTrue(LvcProjectService.listLocalBranches(repo.path()).contains("feature/new"), "new branch name should appear after current branch rename");
        IntegrationTestSupport.assertEquals(featureCommit.commit().getName(), LvcProjectService.localBranchTipCommitId(repo.path(), "feature/new"), "renamed branch tip should keep the old branch commit");
        IntegrationTestSupport.assertEquals(oldBranchHistory, LvcProjectService.listCommits(repo.path()).stream().map(LvcProjectService.CommitInfo::id).toList(), "renamed current branch should keep focused branch history");

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(null, repository.getConfig().getString("lvcBranchStart", oldBranchName, "commit"), "old branch-start metadata should be removed after rename");
            IntegrationTestSupport.assertEquals(repo.second().commit().getName(), repository.getConfig().getString("lvcBranchStart", "feature/new", "commit"), "new branch-start metadata should keep the original start commit");
            IntegrationTestSupport.assertEquals(null, repository.resolve(Constants.R_HEADS + oldBranchName), "old branch ref should be gone after rename");
            IntegrationTestSupport.assertTrue(repository.resolve(Constants.R_HEADS + "feature/new") != null, "new branch ref should exist after rename");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + "feature/new", repository.getConfig().getString("lvc", null, "historyBranch"), "history branch metadata should follow current branch rename");
        }

        LvcProjectService.checkoutBranchToWorkingTree(repo.path(), LvcProjectService.DEFAULT_BRANCH);

        String renamedOtherBranch = LvcProjectService.renameBranch(repo.path(), "feature/new", "feature/renamed");

        IntegrationTestSupport.assertEquals("feature/renamed", renamedOtherBranch, "renamed non-current branch name should be returned");
        IntegrationTestSupport.assertEquals(LvcProjectService.DEFAULT_BRANCH, LvcProjectService.headPointerName(repo.path()), "renaming another branch must not move HEAD");
        IntegrationTestSupport.assertTrue(!LvcProjectService.listLocalBranches(repo.path()).contains("feature/new"), "old non-current branch name should disappear");
        IntegrationTestSupport.assertTrue(LvcProjectService.listLocalBranches(repo.path()).contains("feature/renamed"), "new non-current branch name should appear");

        try (Git git = Git.open(repo.path().toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(null, repository.getConfig().getString("lvcBranchStart", "feature/new", "commit"), "intermediate branch-start metadata should be removed after second rename");
            IntegrationTestSupport.assertEquals(repo.second().commit().getName(), repository.getConfig().getString("lvcBranchStart", "feature/renamed", "commit"), "renamed non-current branch should keep branch-start metadata");
        }

        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "feature/renamed", "Branch already exists");
        assertRenameBranchRejected(repo.path(), "feature/missing", "feature/unused", "Branch does not exist");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, " ", "must not be blank");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "HEAD", "cannot be HEAD");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "refs/heads/bad", "must not start with refs/");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "bad branch", "must not contain whitespace");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, "bad..branch", "Invalid branch name");
        assertRenameBranchRejected(repo.path(), LvcProjectService.DEFAULT_BRANCH, LvcProjectService.DEFAULT_BRANCH, "must be different");
    }
}
