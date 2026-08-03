package me.arnavpmr.lvc;

import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.git.LvcGitRemoteOps;
import me.arnavpmr.lvc.git.LvcGitHistoryOps;
import me.arnavpmr.lvc.git.LvcGitBranchOps;
import me.arnavpmr.lvc.git.LvcCommitInfo;
import me.arnavpmr.lvc.project.LvcProjectPaths;
import me.arnavpmr.lvc.project.LvcProjectCatalog;
import me.arnavpmr.lvc.project.LvcProject;
import static me.arnavpmr.lvc.LvcRepositoryTestSupport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import me.arnavpmr.lvc.storage.LvcRepository;

final class LvcProjectRepositoryIntegrationTest
{
    private LvcProjectRepositoryIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic repo commits use current branch HEAD and history lists newest first", LvcProjectRepositoryIntegrationTest::semanticRepoCommitsUseCurrentBranchHeadAndHistoryListsNewestFirst);
        IntegrationTestSupport.run("project service lists semantic repositories and pushes to a remote", LvcProjectRepositoryIntegrationTest::projectServiceListsSemanticRepositoriesAndPushesToRemote);
        IntegrationTestSupport.run("project repository names cannot escape project root", LvcProjectRepositoryIntegrationTest::projectRepositoryNamesCannotEscapeProjectRoot);
        IntegrationTestSupport.run("project success paths start at the Minecraft directory", LvcProjectRepositoryIntegrationTest::projectSuccessPathsStartAtMinecraftDirectory);
        IntegrationTestSupport.run("project service deletes semantic repositories recursively", LvcProjectRepositoryIntegrationTest::projectServiceDeletesSemanticRepositoriesRecursively);
        IntegrationTestSupport.run("remote URL config can be created and edited", LvcProjectRepositoryIntegrationTest::remoteUrlConfigCanBeCreatedAndEdited);
        IntegrationTestSupport.run("push uses the last active branch while HEAD is detached", LvcProjectRepositoryIntegrationTest::pushUsesLastActiveBranchWhileHeadIsDetached);
    }

    private static void semanticRepoCommitsUseCurrentBranchHeadAndHistoryListsNewestFirst() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-commit-parent-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult first = createInitialCommit(repoDir, "Parent Driven", reader, "ParentOne");
        LvcSemanticRepository.CommitResult second = commitSingleBlock(repoDir, first, reader, "minecraft:dirt", "ParentTwo", "update from world");
        LvcSemanticRepository.CommitResult third = commitSingleBlock(repoDir, second, reader, "minecraft:gold_block", "ParentThree", "ignore stale supplied parent");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH, repository.getFullBranch(), "initial commit should be on the default branch");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsedHead = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(third.commit().getId(), parsedHead.getId(), "HEAD should point at the newest commit");
                IntegrationTestSupport.assertEquals(1, parsedHead.getParentCount(), "new commit should have one parent");
                IntegrationTestSupport.assertEquals(second.commit().getId(), parsedHead.getParent(0).getId(), "commit should parent the current branch HEAD");
            }
        }

        List<LvcCommitInfo> history = LvcGitHistoryOps.listCommits(repoDir);
        IntegrationTestSupport.assertEquals(3, history.size(), "history size");
        IntegrationTestSupport.assertEquals(third.commit().getName(), history.get(0).id(), "newest commit first");
        IntegrationTestSupport.assertEquals("ignore stale supplied parent", history.get(0).message(), "newest commit message");
        IntegrationTestSupport.assertEquals(first.commit().getName(), history.get(2).id(), "oldest commit last");
    }

    private static void projectServiceListsSemanticRepositoriesAndPushesToRemote() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-run-");
        Path reposDir = LvcProjectPaths.reposDirectory(runDir);
        Path validRepo = reposDir.resolve("Valid Project");
        Path invalidRepo = reposDir.resolve("Not Git");
        LvcSemanticRepository.CommitResult commit = createInitialCommit(validRepo, "Valid Project", new FakeWorldReader("minecraft:stone"), "ListValid");
        Files.createDirectories(invalidRepo);

        List<LvcProject> projects = LvcProjectCatalog.listProjects(runDir);
        IntegrationTestSupport.assertEquals(1, projects.size(), "only semantic git repositories should be listed");
        IntegrationTestSupport.assertEquals("Valid Project", projects.get(0).name(), "listed project name");
        IntegrationTestSupport.assertEquals(validRepo, projects.get(0).directory(), "listed project directory");

        Path remoteDir = Files.createTempDirectory("lvc-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcGitRemoteOps.setRemote(validRepo, remoteDir.toUri().toString());
            pushStatuses = LvcGitRemoteOps.push(validRepo);
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(commit.commit().getId(), defaultBranchId, "remote should receive pushed main branch");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH)), "push status should report the pushed branch");
        }
    }

    private static void projectRepositoryNamesCannotEscapeProjectRoot() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-path-root-");
        Path reposRoot = LvcProjectPaths.reposDirectory(runDir).toAbsolutePath().normalize();
        Path directProject = LvcProjectPaths.repositoryDirectory(runDir, "Direct Project").toAbsolutePath().normalize();

        IntegrationTestSupport.assertEquals(reposRoot, directProject.getParent(), "normal project should resolve directly under gitmatica-projects");
        assertRejectedProjectName(runDir, ".");
        assertRejectedProjectName(runDir, "..");
        assertRejectedProjectName(runDir, "../escape");
        assertRejectedProjectName(runDir, "nested/project");
        assertRejectedProjectName(runDir, "nested\\project");
    }

    private static void projectSuccessPathsStartAtMinecraftDirectory() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-display-path-");
        Path repositoryDirectory = LvcProjectPaths.repositoryDirectory(runDir, "Display Project");
        Path displayPath = LvcProjectPaths.minecraftDisplayPath(runDir, repositoryDirectory);

        IntegrationTestSupport.assertEquals(
                Path.of(".minecraft", LvcProjectPaths.REPOS_DIRECTORY, "Display Project"),
                displayPath,
                "project-created message path"
        );

        try
        {
            LvcProjectPaths.minecraftDisplayPath(runDir, runDir.resolveSibling("outside"));
            throw new AssertionError("paths outside the Minecraft directory should be rejected");
        }
        catch (IllegalArgumentException expected)
        {
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("inside the Minecraft game directory"),
                    "outside display path error should explain the rejected path");
        }
    }

    private static void projectServiceDeletesSemanticRepositoriesRecursively() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-delete-run-");
        Path validRepo = LvcProjectPaths.repositoryDirectory(runDir, "Delete Me");
        Path invalidRepo = LvcProjectPaths.reposDirectory(runDir).resolve("Not LVC");

        createInitialCommit(validRepo, "Delete Me", new FakeWorldReader("minecraft:stone"), "DeleteMe");
        Files.createDirectories(validRepo.resolve("scratch/nested"));
        Files.writeString(validRepo.resolve("scratch/nested/untracked.txt"), "delete this", StandardCharsets.UTF_8);
        Files.writeString(validRepo.resolve("scratch/nested/read-only.txt"), "delete this too", StandardCharsets.UTF_8);
        validRepo.resolve("scratch/nested/read-only.txt").toFile().setReadOnly();
        try (var objectFiles = Files.walk(validRepo.resolve(".git/objects")))
        {
            objectFiles.filter(Files::isRegularFile)
                    .findFirst()
                    .ifPresent(path -> path.toFile().setReadOnly());
        }
        Files.createDirectories(invalidRepo.resolve("scratch"));
        Files.writeString(invalidRepo.resolve("scratch/keep.txt"), "keep this", StandardCharsets.UTF_8);

        LvcProjectCatalog.deleteProject(runDir, validRepo);

        IntegrationTestSupport.assertTrue(!Files.exists(validRepo), "delete project should remove the whole repository directory");

        try
        {
            LvcProjectCatalog.deleteProject(runDir, invalidRepo);
            throw new AssertionError("invalid LVC project directory should not be deleted");
        }
        catch (IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("Not a valid LVC project repository"), "invalid delete error should explain rejected directory");
        }

        IntegrationTestSupport.assertTrue(Files.exists(invalidRepo.resolve("scratch/keep.txt")), "invalid project delete must leave files intact");
    }

    private static void remoteUrlConfigCanBeCreatedAndEdited() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-remote-config-");
        createInitialCommit(repoDir, "Remote Config", new FakeWorldReader("minecraft:stone"), "RemoteConfig");

        String firstRemoteUrl = "https://github.com/example/first.git";
        String secondRemoteUrl = "https://github.com/example/second.git";

        IntegrationTestSupport.assertTrue(!LvcGitRemoteOps.hasRemote(repoDir), "new repo should not report a remote");

        LvcGitRemoteOps.setRemote(repoDir, "  " + firstRemoteUrl + "  ");

        IntegrationTestSupport.assertTrue(LvcGitRemoteOps.hasRemote(repoDir), "set remote should make origin available");
        IntegrationTestSupport.assertEquals(firstRemoteUrl, LvcGitRemoteOps.remoteOriginUrl(repoDir), "remote URL should be trimmed before saving");

        LvcGitRemoteOps.setRemote(repoDir, secondRemoteUrl);

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(secondRemoteUrl, repository.getConfig().getString("remote", "origin", "url"), "edited remote URL");
            IntegrationTestSupport.assertEquals("+refs/heads/*:refs/remotes/origin/*", repository.getConfig().getString("remote", "origin", "fetch"), "origin fetch refspec");
            IntegrationTestSupport.assertEquals("origin", repository.getConfig().getString("branch", LvcGitBranchOps.DEFAULT_BRANCH, "remote"), "current branch remote");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH, repository.getConfig().getString("branch", LvcGitBranchOps.DEFAULT_BRANCH, "merge"), "current branch merge ref");
        }

        try
        {
            LvcGitRemoteOps.setRemote(repoDir, "   ");
            throw new AssertionError("blank remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("must not be blank"), "blank remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, LvcGitRemoteOps.remoteOriginUrl(repoDir), "blank edit should not replace existing remote");

        try
        {
            LvcGitRemoteOps.setRemote(repoDir, "git@github.com:example/ssh.git");
            throw new AssertionError("SSH remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("SSH remotes are not supported"), "SSH remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, LvcGitRemoteOps.remoteOriginUrl(repoDir), "SSH edit should not replace existing remote");
    }

    private static void pushUsesLastActiveBranchWhileHeadIsDetached() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Detached Push");
        LvcGitBranchOps.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        Path remoteDir = Files.createTempDirectory("lvc-detached-push-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcGitRemoteOps.setRemote(repo.path(), remoteDir.toUri().toString());
            pushStatuses = LvcGitRemoteOps.push(repo.path());
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(repo.second().commit().getId(), defaultBranchId, "detached push should publish the last active branch tip");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH)), "detached push status should report the pushed branch");
        }

        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "detached push must not move the checked-out HEAD");
    }
}
