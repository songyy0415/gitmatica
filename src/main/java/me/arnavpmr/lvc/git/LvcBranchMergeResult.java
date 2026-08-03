package me.arnavpmr.lvc.git;

import javax.annotation.Nullable;

public record LvcBranchMergeResult(LvcBranchMergeStatus status, String targetBranch, String sourceBranch,
                                   String commitId, @Nullable String previousHead, int regionCount, int mergedChunks)
{
}
