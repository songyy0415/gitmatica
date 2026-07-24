package me.niicide.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.niicide.lvc.LvcDiagnostics;

public final class LvcGitUndoOps
{
    private LvcGitUndoOps()
    {
    }

    public static LvcLatestCommitUndoTarget latestCommitUndoTarget(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            return latestCommitUndoTarget(git.getRepository(), revWalk);
        }
    }

    public static LvcLatestCommitUndoResult undoLatestCommitKeepChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        return undoLatestCommit(repositoryDirectory, ResetCommand.ResetType.MIXED, "keep changes");
    }

    public static LvcLatestCommitUndoResult undoLatestCommitDeleteChanges(Path repositoryDirectory)
            throws GitAPIException, IOException
    {
        return undoLatestCommit(repositoryDirectory, ResetCommand.ResetType.HARD, "delete changes");
    }

    private static LvcLatestCommitUndoResult undoLatestCommit(Path repositoryDirectory,
                                                              ResetCommand.ResetType resetType,
                                                              String operationDescription)
            throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            LvcLatestCommitUndoTarget target = latestCommitUndoTarget(repository, revWalk);

            LvcDiagnostics.debug(
                    "LvcGitUndoOps: undo latest commit {} repo='{}' branch='{}' commit='{}' parent='{}'",
                    operationDescription,
                    repositoryDirectory,
                    target.branchName(),
                    target.commitId(),
                    target.parentCommitId()
            );
            git.reset()
                    .setMode(resetType)
                    .setRef(target.parentCommitId())
                    .call();
            LvcGitBranchMetadata.rememberCurrentBranch(repository);

            return new LvcLatestCommitUndoResult(target.commitId(), target.parentCommitId(), target.branchName());
        }
    }

    private static LvcLatestCommitUndoTarget latestCommitUndoTarget(Repository repository, RevWalk revWalk)
            throws IOException
    {
        String fullBranch = LvcGitBranchOps.currentBranch(repository);
        String branchName = fullBranch.substring(Constants.R_HEADS.length());
        ObjectId headId = LvcGitBranchOps.resolveHeadCommitId(repository);

        if (headId == null)
        {
            throw new IOException("Create the first version before deleting a version");
        }

        RevCommit head = revWalk.parseCommit(headId);

        if (head.getParentCount() == 0)
        {
            throw new IOException("Cannot delete the only version");
        }

        RevCommit parent = revWalk.parseCommit(head.getParent(0).getId());
        return new LvcLatestCommitUndoTarget(head.getName(), parent.getName(), branchName);
    }
}
