package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;

import java.nio.file.Files;
import java.nio.file.Path;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.task.LvcOperationJournal;

final class LvcRepositoryTestSupport
{
    private LvcRepositoryTestSupport()
    {
    }

    static String expectedResolvedConflictState(LvcProjectService.BranchMergeConflictResolution resolution)
    {
        return switch (resolution)
        {
            case BASE -> "minecraft:stone";
            case INCOMING -> "minecraft:gold_block";
            case YOURS -> "minecraft:dirt";
        };
    }

    static void assertRejectedProjectName(Path runDir, String name)
    {
        try
        {
            LvcProjectService.repositoryDirectory(runDir, name);
            throw new AssertionError("project name should be rejected: " + name);
        }
        catch (IllegalArgumentException expected)
        {
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("LVC repository name"), "rejection should explain LVC repository name constraint");
        }
    }

    static void assertCreateBranchRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.createAndCheckoutBranch(repoDir, branchName);
            throw new AssertionError("branch name should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch rejection should explain: " + expectedMessage);
        }
    }

    static void assertDeleteBranchRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.deleteBranch(repoDir, branchName);
            throw new AssertionError("branch delete should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch delete rejection should explain: " + expectedMessage);
        }
    }

    static void assertRenameBranchRejected(Path repoDir, String oldName, String newName, String expectedMessage)
    {
        try
        {
            LvcProjectService.renameBranch(repoDir, oldName, newName);
            throw new AssertionError("branch rename should be rejected: " + oldName + " -> " + newName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch rename rejection should explain: " + expectedMessage);
        }
    }

    static void assertBranchTipRejected(Path repoDir, String branchName, String expectedMessage)
    {
        try
        {
            LvcProjectService.localBranchTipCommitId(repoDir, branchName);
            throw new AssertionError("branch tip lookup should be rejected: " + branchName);
        }
        catch (Exception e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains(expectedMessage), "branch tip rejection should explain: " + expectedMessage);
        }
    }

    static void assertMergeRestoreJournal(Path repoDir, String targetCommit, String targetBranch,
                                                  String sourceBranch, String previousHead) throws Exception
    {
        LvcOperationJournal.Entry entry = LvcOperationJournal.read(repoDir);
        IntegrationTestSupport.assertNotNull(entry, "successful merge should leave a recovery journal until world restore completes");
        IntegrationTestSupport.assertEquals(LvcOperationJournal.Operation.MERGE.name(), entry.operation(), "merge journal operation");
        IntegrationTestSupport.assertEquals("restore", entry.phase(), "merge journal phase");
        IntegrationTestSupport.assertEquals(targetCommit, entry.targetCommit(), "merge journal target commit");
        IntegrationTestSupport.assertEquals(targetBranch, entry.targetBranch(), "merge journal target branch");
        IntegrationTestSupport.assertEquals(sourceBranch, entry.sourceBranch(), "merge journal source branch");
        IntegrationTestSupport.assertEquals(previousHead, entry.previousHead(), "merge journal previous head");
        IntegrationTestSupport.assertTrue(entry.checksum() != null && !entry.checksum().isBlank(), "merge journal checksum");
    }

    static SemanticRepo createTwoCommitRepo(String name) throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-git-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult first = createInitialCommit(repoDir, name, reader, name + "One");
        LvcSemanticRepository.CommitResult second = commitSingleBlock(repoDir, first, reader, "minecraft:dirt", name + "Two", "second");
        return new SemanticRepo(repoDir, reader, first, second);
    }

    static LvcSemanticRepository.CommitResult createInitialCommit(Path repoDir, String projectName, FakeWorldReader reader, String playerName) throws Exception
    {
        return LvcSemanticRepository.initProject(repoDir, projectName, singleLineSite(), placementAt(0, 0, 0), reader, player(playerName));
    }

    static LvcSemanticRepository.CommitResult commitSingleBlock(Path repoDir, LvcSemanticRepository.CommitResult previous,
                                                                        FakeWorldReader reader, String blockState,
                                                                        String playerName, String message) throws Exception
    {
        reader.setBlock(new LvcIntPosition(0, 0, 0), blockState);
        return LvcSemanticRepository.commitSite(repoDir, previous.manifest(), "main", placementAt(0, 0, 0), reader, player(playerName), message);
    }

    static LvcSemanticRepository.CommitResult commitCurrent(Path repoDir, FakeWorldReader reader,
                                                                    String playerName, String message) throws Exception
    {
        return LvcSemanticRepository.commitSite(repoDir, LvcSemanticRepository.readManifest(repoDir),
                "main", placementAt(0, 0, 0), reader, player(playerName), message);
    }

    static LvcChunk readOnlyChunk(Path repoDir) throws Exception
    {
        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);
        String objectId = manifest.site("main").fullHashes().values().iterator().next();
        return LvcChunkCodec.decode(LvcChunkStore.readObject(repoDir, objectId));
    }

    record SemanticRepo(Path path, FakeWorldReader reader,
                                LvcSemanticRepository.CommitResult first,
                                LvcSemanticRepository.CommitResult second)
    {
    }
}
