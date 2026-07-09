package me.niicide.lvc;

import static me.niicide.lvc.LvcRepositoryTestSupport.*;

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
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;

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
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, repository.getFullBranch(), "initial commit should be on the default branch");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit parsedHead = revWalk.parseCommit(repository.resolve(Constants.HEAD));
                IntegrationTestSupport.assertEquals(third.commit().getId(), parsedHead.getId(), "HEAD should point at the newest commit");
                IntegrationTestSupport.assertEquals(1, parsedHead.getParentCount(), "new commit should have one parent");
                IntegrationTestSupport.assertEquals(second.commit().getId(), parsedHead.getParent(0).getId(), "commit should parent the current branch HEAD");
            }
        }

        List<LvcProjectService.CommitInfo> history = LvcProjectService.listCommits(repoDir);
        IntegrationTestSupport.assertEquals(3, history.size(), "history size");
        IntegrationTestSupport.assertEquals(third.commit().getName(), history.get(0).id(), "newest commit first");
        IntegrationTestSupport.assertEquals("ignore stale supplied parent", history.get(0).message(), "newest commit message");
        IntegrationTestSupport.assertEquals(first.commit().getName(), history.get(2).id(), "oldest commit last");
    }

    private static void projectServiceListsSemanticRepositoriesAndPushesToRemote() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-run-");
        Path reposDir = LvcProjectService.reposDirectory(runDir);
        Path validRepo = reposDir.resolve("Valid Project");
        Path invalidRepo = reposDir.resolve("Not Git");
        LvcSemanticRepository.CommitResult commit = createInitialCommit(validRepo, "Valid Project", new FakeWorldReader("minecraft:stone"), "ListValid");
        Files.createDirectories(invalidRepo);

        List<LvcProjectService.Project> projects = LvcProjectService.listProjects(runDir);
        IntegrationTestSupport.assertEquals(1, projects.size(), "only semantic git repositories should be listed");
        IntegrationTestSupport.assertEquals("Valid Project", projects.get(0).name(), "listed project name");
        IntegrationTestSupport.assertEquals(validRepo, projects.get(0).directory(), "listed project directory");

        Path remoteDir = Files.createTempDirectory("lvc-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcProjectService.setRemote(validRepo, remoteDir.toUri().toString());
            pushStatuses = LvcProjectService.push(validRepo);
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(commit.commit().getId(), defaultBranchId, "remote should receive pushed main branch");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH)), "push status should report the pushed branch");
        }
    }

    private static void projectRepositoryNamesCannotEscapeProjectRoot() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-path-root-");
        Path reposRoot = LvcProjectService.reposDirectory(runDir).toAbsolutePath().normalize();
        Path directProject = LvcProjectService.repositoryDirectory(runDir, "Direct Project").toAbsolutePath().normalize();

        IntegrationTestSupport.assertEquals(reposRoot, directProject.getParent(), "normal project should resolve directly under gitmatica-projects");
        assertRejectedProjectName(runDir, ".");
        assertRejectedProjectName(runDir, "..");
        assertRejectedProjectName(runDir, "../escape");
        assertRejectedProjectName(runDir, "nested/project");
        assertRejectedProjectName(runDir, "nested\\project");
    }

    private static void projectServiceDeletesSemanticRepositoriesRecursively() throws Exception
    {
        Path runDir = Files.createTempDirectory("lvc-delete-run-");
        Path validRepo = LvcProjectService.repositoryDirectory(runDir, "Delete Me");
        Path invalidRepo = LvcProjectService.reposDirectory(runDir).resolve("Not LVC");

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

        LvcProjectService.deleteProjectRepository(runDir, validRepo);

        IntegrationTestSupport.assertTrue(!Files.exists(validRepo), "delete project should remove the whole repository directory");

        try
        {
            LvcProjectService.deleteProjectRepository(runDir, invalidRepo);
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

        IntegrationTestSupport.assertTrue(!LvcProjectService.hasRemote(repoDir), "new repo should not report a remote");

        LvcProjectService.setRemote(repoDir, "  " + firstRemoteUrl + "  ");

        IntegrationTestSupport.assertTrue(LvcProjectService.hasRemote(repoDir), "set remote should make origin available");
        IntegrationTestSupport.assertEquals(firstRemoteUrl, LvcProjectService.remoteOriginUrl(repoDir), "remote URL should be trimmed before saving");

        LvcProjectService.setRemote(repoDir, secondRemoteUrl);

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            IntegrationTestSupport.assertEquals(secondRemoteUrl, repository.getConfig().getString("remote", "origin", "url"), "edited remote URL");
            IntegrationTestSupport.assertEquals("+refs/heads/*:refs/remotes/origin/*", repository.getConfig().getString("remote", "origin", "fetch"), "origin fetch refspec");
            IntegrationTestSupport.assertEquals("origin", repository.getConfig().getString("branch", LvcProjectService.DEFAULT_BRANCH, "remote"), "current branch remote");
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, repository.getConfig().getString("branch", LvcProjectService.DEFAULT_BRANCH, "merge"), "current branch merge ref");
        }

        try
        {
            LvcProjectService.setRemote(repoDir, "   ");
            throw new AssertionError("blank remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("must not be blank"), "blank remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, LvcProjectService.remoteOriginUrl(repoDir), "blank edit should not replace existing remote");

        try
        {
            LvcProjectService.setRemote(repoDir, "git@github.com:example/ssh.git");
            throw new AssertionError("SSH remote URL should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("SSH remotes are not supported"), "SSH remote error should explain the rejected input");
        }

        IntegrationTestSupport.assertEquals(secondRemoteUrl, LvcProjectService.remoteOriginUrl(repoDir), "SSH edit should not replace existing remote");
    }

    private static void pushUsesLastActiveBranchWhileHeadIsDetached() throws Exception
    {
        SemanticRepo repo = createTwoCommitRepo("Detached Push");
        LvcProjectService.checkoutCommitToWorkingTree(repo.path(), repo.first().commit().getName());

        Path remoteDir = Files.createTempDirectory("lvc-detached-push-remote-").resolve("remote.git");
        List<String> pushStatuses;
        try (Git ignored = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call())
        {
            LvcProjectService.setRemote(repo.path(), remoteDir.toUri().toString());
            pushStatuses = LvcProjectService.push(repo.path());
        }

        try (Repository remoteRepository = new FileRepositoryBuilder().setGitDir(remoteDir.toFile()).build())
        {
            ObjectId defaultBranchId = remoteRepository.resolve(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH);
            IntegrationTestSupport.assertEquals(repo.second().commit().getId(), defaultBranchId, "detached push should publish the last active branch tip");
            IntegrationTestSupport.assertTrue(pushStatuses.stream().anyMatch(status -> status.contains(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH)), "detached push status should report the pushed branch");
        }

        IntegrationTestSupport.assertEquals(repo.first().commit().getId(), LvcRepository.resolveHead(repo.path()), "detached push must not move the checked-out HEAD");
    }
}
