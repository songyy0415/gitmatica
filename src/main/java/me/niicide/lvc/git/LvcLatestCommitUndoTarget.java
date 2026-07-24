package me.niicide.lvc.git;

public record LvcLatestCommitUndoTarget(String commitId, String parentCommitId, String branchName)
{
    public String shortCommitId()
    {
        return shortCommit(this.commitId);
    }

    public String shortParentCommitId()
    {
        return shortCommit(this.parentCommitId);
    }

    private static String shortCommit(String commitId)
    {
        return commitId.substring(0, Math.min(8, commitId.length()));
    }
}
