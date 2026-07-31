package me.arnavpmr.lvc.task;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

sealed interface LvcRemoteApplyRequest
        permits LvcRemoteApplyRequest.CheckoutCommit, LvcRemoteApplyRequest.CheckoutBranch,
                LvcRemoteApplyRequest.Discard, LvcRemoteApplyRequest.Clear,
                LvcRemoteApplyRequest.DeleteVersion, LvcRemoteApplyRequest.Merge
{
    LvcRemoteServerApplyTask.Mode mode();

    @Nullable
    default String targetCommitId()
    {
        return null;
    }

    @Nullable
    default String targetBranchName()
    {
        return null;
    }

    @Nullable
    default String sourceBranchName()
    {
        return null;
    }

    @Nullable
    default String mergePreviousHead()
    {
        return null;
    }

    @Nullable
    default List<BlockPos> furnaceXpCleanupCandidates()
    {
        return null;
    }

    record CheckoutCommit(String targetCommitId,
                          @Nullable List<BlockPos> furnaceXpCleanupCandidates) implements LvcRemoteApplyRequest
    {
        public CheckoutCommit
        {
            targetCommitId = requireText(targetCommitId, "targetCommitId");
            furnaceXpCleanupCandidates = copyNullable(furnaceXpCleanupCandidates);
        }

        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.CHECKOUT;
        }
    }

    record CheckoutBranch(String targetCommitId, String targetBranchName,
                          @Nullable List<BlockPos> furnaceXpCleanupCandidates) implements LvcRemoteApplyRequest
    {
        public CheckoutBranch
        {
            targetCommitId = requireText(targetCommitId, "targetCommitId");
            targetBranchName = requireText(targetBranchName, "targetBranchName");
            furnaceXpCleanupCandidates = copyNullable(furnaceXpCleanupCandidates);
        }

        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.CHECKOUT_BRANCH;
        }
    }

    record Discard(@Nullable String targetCommitId) implements LvcRemoteApplyRequest
    {
        public Discard
        {
            targetCommitId = normalize(targetCommitId);
        }

        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.DISCARD;
        }
    }

    record Clear() implements LvcRemoteApplyRequest
    {
        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.CLEAR;
        }
    }

    record DeleteVersion(String targetCommitId, String targetBranchName) implements LvcRemoteApplyRequest
    {
        public DeleteVersion
        {
            targetCommitId = requireText(targetCommitId, "targetCommitId");
            targetBranchName = requireText(targetBranchName, "targetBranchName");
        }

        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.DELETE_VERSION;
        }
    }

    record Merge(String targetCommitId, @Nullable String targetBranchName,
                 @Nullable String sourceBranchName,
                 @Nullable String mergePreviousHead) implements LvcRemoteApplyRequest
    {
        public Merge
        {
            targetCommitId = requireText(targetCommitId, "targetCommitId");
            targetBranchName = normalize(targetBranchName);
            sourceBranchName = normalize(sourceBranchName);
            mergePreviousHead = normalize(mergePreviousHead);
        }

        @Override
        public LvcRemoteServerApplyTask.Mode mode()
        {
            return LvcRemoteServerApplyTask.Mode.MERGE;
        }
    }

    @Nullable
    private static List<BlockPos> copyNullable(@Nullable List<BlockPos> positions)
    {
        return positions == null ? null : List.copyOf(positions);
    }

    private static String requireText(String value, String label)
    {
        return Objects.requireNonNull(normalize(value), label);
    }

    @Nullable
    private static String normalize(@Nullable String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
