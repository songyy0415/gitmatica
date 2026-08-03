package me.arnavpmr.lvc.overlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import me.arnavpmr.lvc.git.LvcGitTreeReader;

public final class LvcTrackingOverlayRevisionResolver
{
    private LvcTrackingOverlayRevisionResolver()
    {
    }

    public static LvcTrackingOverlayRevisionTarget resolve(Path repositoryDirectory,
                                                           LvcTrackingOverlayRevision revision) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(revision, "revision");

        try (Git git = Git.open(repositoryDirectory.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository()))
        {
            RevCommit head = LvcGitTreeReader.resolveCommit(git.getRepository(), revWalk, "HEAD");

            if (revision == LvcTrackingOverlayRevision.CURRENT)
            {
                return new LvcTrackingOverlayRevisionTarget(head.getName(), head.getName(), false);
            }

            if (head.getParentCount() == 0)
            {
                return new LvcTrackingOverlayRevisionTarget(head.getName(), null, true);
            }

            RevCommit parent = revWalk.parseCommit(head.getParent(0).getId());
            return new LvcTrackingOverlayRevisionTarget(head.getName(), parent.getName(), false);
        }
    }

    public static String displayName(String projectName, LvcTrackingOverlayRevisionTarget target)
    {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(target, "target");

        if (target.airSchematic())
        {
            return projectName + " @ " + shortCommit(target.headCommitId()) + "^ (air)";
        }

        return projectName + " @ " + shortCommit(Objects.requireNonNull(target.sourceCommitId(), "sourceCommitId"));
    }

    private static String shortCommit(String commitId)
    {
        return commitId.substring(0, Math.min(8, commitId.length()));
    }
}
