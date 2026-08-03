package me.arnavpmr.lvc.git;

import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

final class LvcGitBranchMetadata
{
    private static final String GIT_CONFIG_SECTION = "lvc";
    private static final String GIT_CONFIG_HISTORY_BRANCH_KEY = "historyBranch";
    private static final String GIT_CONFIG_BRANCH_START_SECTION = "lvcBranchStart";
    private static final String GIT_CONFIG_BRANCH_START_KEY = "commit";

    private LvcGitBranchMetadata()
    {
    }

    static String historyBranchRef(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (isLocalBranch(fullBranch))
        {
            rememberHistoryBranch(repository, fullBranch);
            return fullBranch;
        }

        String configuredBranch = repository.getConfig().getString(
                GIT_CONFIG_SECTION,
                null,
                GIT_CONFIG_HISTORY_BRANCH_KEY
        );

        if (configuredBranch != null && !configuredBranch.isBlank() && repository.resolve(configuredBranch) != null)
        {
            return configuredBranch;
        }

        String defaultBranch = Constants.R_HEADS + LvcGitBranchOps.DEFAULT_BRANCH;

        if (repository.resolve(defaultBranch) != null)
        {
            rememberHistoryBranch(repository, defaultBranch);
            return defaultBranch;
        }

        List<Ref> localBranches = repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);

        if (!localBranches.isEmpty())
        {
            String fallbackBranch = localBranches.get(0).getName();
            rememberHistoryBranch(repository, fallbackBranch);
            return fallbackBranch;
        }

        return Constants.HEAD;
    }

    static String pushBranchRef(Repository repository) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (isLocalBranch(branch))
        {
            return branch;
        }

        throw new IOException("LVC repository has no local branch to push");
    }

    static void configureRemoteTracking(Repository repository, StoredConfig config) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (!isLocalBranch(branch))
        {
            return;
        }

        String shortBranchName = branch.substring(Constants.R_HEADS.length());
        config.setString("branch", shortBranchName, "remote", "origin");
        config.setString("branch", shortBranchName, "merge", branch);
    }

    static void rememberCurrentBranch(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (isLocalBranch(fullBranch))
        {
            rememberHistoryBranch(repository, fullBranch);
        }
    }

    static void rememberBranchStart(Repository repository, String branchName, ObjectId startCommit) throws IOException
    {
        StoredConfig config = repository.getConfig();

        if (!startCommit.name().equals(branchStartCommit(repository, branchName)))
        {
            config.setString(GIT_CONFIG_BRANCH_START_SECTION, branchName, GIT_CONFIG_BRANCH_START_KEY, startCommit.name());
            config.save();
        }
    }

    static void clearBranchStart(Repository repository, String branchName) throws IOException
    {
        StoredConfig config = repository.getConfig();
        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, branchName);
        config.save();
    }

    static void moveBranchMetadata(Repository repository, String oldName, String newName) throws IOException
    {
        StoredConfig config = repository.getConfig();
        String startCommit = branchStartCommit(repository, oldName);
        String oldFullBranch = Constants.R_HEADS + oldName;
        String newFullBranch = Constants.R_HEADS + newName;

        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, newName);
        config.unsetSection(GIT_CONFIG_BRANCH_START_SECTION, oldName);

        if (startCommit != null && !startCommit.isBlank())
        {
            config.setString(GIT_CONFIG_BRANCH_START_SECTION, newName, GIT_CONFIG_BRANCH_START_KEY, startCommit);
        }

        if (oldFullBranch.equals(config.getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY)))
        {
            config.setString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY, newFullBranch);
        }

        config.save();
    }

    @Nullable
    static String branchStartCommit(Repository repository, String branchName)
    {
        return repository.getConfig().getString(
                GIT_CONFIG_BRANCH_START_SECTION,
                branchName,
                GIT_CONFIG_BRANCH_START_KEY
        );
    }

    static void rememberHistoryBranch(Repository repository, String fullBranch) throws IOException
    {
        StoredConfig config = repository.getConfig();

        if (!fullBranch.equals(config.getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY)))
        {
            config.setString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY, fullBranch);
            config.save();
        }
    }

    private static boolean isLocalBranch(@Nullable String branch)
    {
        return branch != null && branch.startsWith(Constants.R_HEADS);
    }
}
