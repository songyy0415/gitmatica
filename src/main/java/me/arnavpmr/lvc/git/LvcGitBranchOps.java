package me.arnavpmr.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import me.arnavpmr.lvc.LvcDiagnostics;

public final class LvcGitBranchOps
{
    public static final String DEFAULT_BRANCH = "main";

    private LvcGitBranchOps()
    {
    }

    public static void checkoutCommitToWorkingTree(Path repositoryDirectory, String commitId) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(commitId, "commitId");

        if (commitId.isBlank())
        {
            throw new IllegalArgumentException("Commit id must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcGitBranchMetadata.rememberCurrentBranch(git.getRepository());
            git.checkout().setName(commitId.trim()).call();
        }
    }

    public static void checkoutBranchToWorkingTree(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcDiagnostics.debug("LvcGitBranchOps: checking out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);
            git.checkout().setName(normalizedBranchName).call();
            LvcGitBranchMetadata.rememberCurrentBranch(git.getRepository());
            LvcDiagnostics.debug("LvcGitBranchOps: checked out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);
        }
    }

    public static String createAndCheckoutBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        String normalizedBranchName = validateNewBranchName(repositoryDirectory, branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId head = resolveHeadCommitId(repository);

            if (head == null)
            {
                throw new IOException("Create the first version before creating a branch");
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: creating branch repo='{}' branch='{}' start='{}'",
                    repositoryDirectory,
                    normalizedBranchName,
                    head.name()
            );
            git.checkout()
                    .setCreateBranch(true)
                    .setName(normalizedBranchName)
                    .setStartPoint(Constants.HEAD)
                    .call();
            LvcGitBranchMetadata.rememberBranchStart(repository, normalizedBranchName, head);
            LvcGitBranchMetadata.rememberHistoryBranch(repository, Constants.R_HEADS + normalizedBranchName);
            LvcDiagnostics.debug("LvcGitBranchOps: created and checked out branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);

            return normalizedBranchName;
        }
    }

    public static String deleteBranch(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + normalizedBranchName;
            Ref branchRef = repository.exactRef(fullBranch);

            if (branchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedBranchName);
            }

            if (fullBranch.equals(repository.getFullBranch()))
            {
                throw new IllegalArgumentException("Cannot delete the checked-out branch: " + normalizedBranchName);
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: deleting branch repo='{}' branch='{}' tip='{}'",
                    repositoryDirectory,
                    normalizedBranchName,
                    branchRef.getObjectId() == null ? "<unknown>" : branchRef.getObjectId().name()
            );
            git.branchDelete()
                    .setBranchNames(normalizedBranchName)
                    .setForce(true)
                    .call();
            LvcGitBranchMetadata.clearBranchStart(repository, normalizedBranchName);
            LvcDiagnostics.debug("LvcGitBranchOps: deleted branch repo='{}' branch='{}'", repositoryDirectory, normalizedBranchName);

            return normalizedBranchName;
        }
    }

    public static String renameBranch(Path repositoryDirectory, String oldName, String newName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(oldName, "oldName");
        Objects.requireNonNull(newName, "newName");

        String normalizedOldName = normalizeLocalBranchName(oldName);
        String normalizedNewCandidate = normalizeLocalBranchName(newName);

        if (normalizedOldName.equals(normalizedNewCandidate))
        {
            throw new IllegalArgumentException("New branch name must be different");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String normalizedNewName = validateNewBranchName(repository, newName);
            String fullOldBranch = Constants.R_HEADS + normalizedOldName;
            Ref oldBranchRef = repository.exactRef(fullOldBranch);

            if (oldBranchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedOldName);
            }

            LvcDiagnostics.debug(
                    "LvcGitBranchOps: renaming branch repo='{}' old='{}' new='{}' tip='{}' current='{}'",
                    repositoryDirectory,
                    normalizedOldName,
                    normalizedNewName,
                    oldBranchRef.getObjectId() == null ? "<unknown>" : oldBranchRef.getObjectId().name(),
                    repository.getFullBranch()
            );
            git.branchRename()
                    .setOldName(fullOldBranch)
                    .setNewName(normalizedNewName)
                    .call();
            LvcGitBranchMetadata.moveBranchMetadata(repository, normalizedOldName, normalizedNewName);
            LvcDiagnostics.debug("LvcGitBranchOps: renamed branch repo='{}' old='{}' new='{}'", repositoryDirectory, normalizedOldName, normalizedNewName);

            return normalizedNewName;
        }
    }

    public static String localBranchTipCommitId(Path repositoryDirectory, String branchName) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + normalizedBranchName;
            Ref branchRef = repository.exactRef(fullBranch);

            if (branchRef == null)
            {
                throw new IllegalArgumentException("Branch does not exist: " + normalizedBranchName);
            }

            ObjectId tip = repository.resolve(fullBranch + "^{commit}");

            if (tip == null)
            {
                throw new IOException("Branch has no commit: " + normalizedBranchName);
            }

            LvcDiagnostics.debug("LvcGitBranchOps: resolved branch tip repo='{}' branch='{}' tip='{}'",
                    repositoryDirectory, normalizedBranchName, tip.name());
            return tip.name();
        }
    }

    public static boolean headMatchesCommit(Path repositoryDirectory, String commitId) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            Repository repository = git.getRepository();
            ObjectId head = resolveHeadCommitId(repository);

            return head != null && head.equals(LvcGitTreeReader.resolveCommit(repository, revWalk, commitId).getId());
        }
    }

    public static boolean reattachHeadToBranchIfAtTip(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        String shortBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = Constants.R_HEADS + shortBranchName;
            ObjectId head = resolveHeadCommitId(repository);
            ObjectId branchTip = repository.resolve(fullBranch + "^{commit}");

            if (head == null || branchTip == null || !head.equals(branchTip))
            {
                return false;
            }

            git.checkout().setName(shortBranchName).call();
            LvcGitBranchMetadata.rememberHistoryBranch(repository, fullBranch);
            return true;
        }
    }

    public static String headPointerName(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String fullBranch = repository.getFullBranch();

            if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
            {
                return fullBranch.substring(Constants.R_HEADS.length());
            }

            ObjectId head = resolveHeadCommitId(repository);

            if (head == null)
            {
                return "unknown";
            }

            String commitId = head.name();
            return commitId.substring(0, Math.min(8, commitId.length()));
        }
    }

    public static boolean isDetachedHead(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String fullBranch = git.getRepository().getFullBranch();
            return fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS);
        }
    }

    public static String preferredCheckoutBranchName(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String branch = LvcGitBranchMetadata.historyBranchRef(git.getRepository());

            if (branch.startsWith(Constants.R_HEADS))
            {
                return branch.substring(Constants.R_HEADS.length());
            }

            throw new IOException("LVC repository has no local branch to checkout");
        }
    }

    public static List<String> listLocalBranches(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            List<String> branches = new ArrayList<>();

            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
            {
                addLocalBranchName(branches, ref.getName());
            }

            addLocalBranchName(branches, repository.getFullBranch());
            branches.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
            LvcDiagnostics.debug("LvcGitBranchOps: listed local branches repo='{}' count={} head='{}'",
                    repositoryDirectory, branches.size(), repository.getFullBranch());

            return List.copyOf(branches);
        }
    }

    public static String validateNewBranchName(Path repositoryDirectory, String branchName) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            return validateNewBranchName(git.getRepository(), branchName);
        }
    }

    public static boolean hasUncommittedChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Status status = git.status().call();
            return !status.getAdded().isEmpty() ||
                    !status.getChanged().isEmpty() ||
                    !status.getConflicting().isEmpty() ||
                    !status.getMissing().isEmpty() ||
                    !status.getModified().isEmpty() ||
                    !status.getRemoved().isEmpty();
        }
    }

    public static void resetWorkingTreeToHead(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    public static void checkoutBranchAndResetToCommit(Path repositoryDirectory, String branchName, String commitId) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");
        Objects.requireNonNull(commitId, "commitId");

        if (commitId.isBlank())
        {
            throw new IllegalArgumentException("Commit id must not be blank");
        }

        String normalizedBranchName = normalizeLocalBranchName(branchName);

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            LvcDiagnostics.debug("LvcGitBranchOps: resetting branch repo='{}' branch='{}' commit='{}'",
                    repositoryDirectory, normalizedBranchName, commitId);
            git.checkout().setName(normalizedBranchName).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitId.trim()).call();
            LvcGitBranchMetadata.rememberCurrentBranch(git.getRepository());
            LvcDiagnostics.debug("LvcGitBranchOps: reset branch repo='{}' branch='{}' commit='{}'",
                    repositoryDirectory, normalizedBranchName, commitId);
        }
    }

    static ObjectId resolveHeadCommitId(Repository repository) throws IOException
    {
        ObjectId head = repository.resolve(Constants.HEAD + "^{commit}");

        if (head == null)
        {
            head = repository.resolve(Constants.HEAD);
        }

        return head;
    }

    static String normalizeLocalBranchName(String branchName)
    {
        String normalizedBranchName = Objects.requireNonNull(branchName, "branchName").trim();

        if (normalizedBranchName.startsWith(Constants.R_HEADS))
        {
            normalizedBranchName = normalizedBranchName.substring(Constants.R_HEADS.length());
        }

        if (normalizedBranchName.isBlank())
        {
            throw new IllegalArgumentException("Branch name must not be blank");
        }

        return normalizedBranchName;
    }

    static String currentBranch(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS))
        {
            throw new IOException("LVC repository is not on a local branch");
        }

        return fullBranch;
    }

    private static String validateNewBranchName(Repository repository, String branchName) throws IOException
    {
        Objects.requireNonNull(branchName, "branchName");

        String requestedBranchName = branchName.trim();
        String normalizedBranchName = normalizeLocalBranchName(branchName);

        if (normalizedBranchName.equalsIgnoreCase(Constants.HEAD))
        {
            throw new IllegalArgumentException("Branch name cannot be HEAD");
        }

        if (requestedBranchName.startsWith("refs/"))
        {
            throw new IllegalArgumentException("Branch name must not start with refs/");
        }

        if (normalizedBranchName.chars().anyMatch(Character::isWhitespace))
        {
            throw new IllegalArgumentException("Branch name must not contain whitespace");
        }

        String fullBranch = Constants.R_HEADS + normalizedBranchName;

        if (!Repository.isValidRefName(fullBranch))
        {
            throw new IllegalArgumentException("Invalid branch name: " + normalizedBranchName);
        }

        if (repository.exactRef(fullBranch) != null || localBranchExistsIgnoringCase(repository, normalizedBranchName))
        {
            throw new IllegalArgumentException("Branch already exists: " + normalizedBranchName);
        }

        return normalizedBranchName;
    }

    private static void addLocalBranchName(List<String> branches, @Nullable String fullRef)
    {
        if (fullRef == null || !fullRef.startsWith(Constants.R_HEADS))
        {
            return;
        }

        String branchName = fullRef.substring(Constants.R_HEADS.length());

        if (!branchName.isBlank() && !branches.contains(branchName))
        {
            branches.add(branchName);
        }
    }

    private static boolean localBranchExistsIgnoringCase(Repository repository, String branchName) throws IOException
    {
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
        {
            String existingBranchName = ref.getName().substring(Constants.R_HEADS.length());

            if (existingBranchName.equalsIgnoreCase(branchName))
            {
                return true;
            }
        }

        return false;
    }

}
