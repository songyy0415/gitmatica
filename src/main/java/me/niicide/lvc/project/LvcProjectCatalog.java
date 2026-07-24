package me.niicide.lvc.project;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.git.LvcGitHistoryOps;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.storage.LvcSemanticRepository;

public final class LvcProjectCatalog
{
    private static final int DELETE_RETRY_ATTEMPTS = 6;
    private static final long DELETE_RETRY_DELAY_MILLIS = 25L;
    private static final ConcurrentMap<Path, CachedProjectSummary> PROJECT_SUMMARY_CACHE = new ConcurrentHashMap<>();

    private LvcProjectCatalog()
    {
    }

    public static List<LvcProject> listProjects(Path gameRunDirectory) throws IOException
    {
        Path reposDirectory = LvcProjectPaths.reposDirectory(gameRunDirectory);

        if (!Files.isDirectory(reposDirectory))
        {
            return List.of();
        }

        List<LvcProject> projects = new ArrayList<>();

        try (var stream = Files.list(reposDirectory))
        {
            for (Path candidate : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList())
            {
                if (isProjectRepository(candidate))
                {
                    projects.add(new LvcProject(candidate.getFileName().toString(), candidate));
                }
            }
        }

        return List.copyOf(projects);
    }

    public static void deleteProject(Path gameRunDirectory, Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        Path reposRoot = LvcProjectPaths.reposDirectory(gameRunDirectory).toAbsolutePath().normalize();
        Path target = repositoryDirectory.toAbsolutePath().normalize();

        if (!target.startsWith(reposRoot) || target.equals(reposRoot))
        {
            throw new IOException("LVC project must be under " + reposRoot);
        }

        if (!isProjectRepository(target))
        {
            throw new IOException("Not a valid LVC project repository: " + target);
        }

        LvcDiagnostics.info("LvcProjectCatalog: deleting LVC project repository '{}'", target);
        PROJECT_SUMMARY_CACHE.remove(target);
        closeTrackingOverlay(target);
        flushJGitFileCache(target);
        deleteRecursively(target);
        LvcDiagnostics.info("LvcProjectCatalog: deleted LVC project repository '{}'", target);
    }

    public static LvcProjectSummary summarize(LvcProject project) throws IOException, GitAPIException
    {
        Objects.requireNonNull(project, "project");
        Path repositoryDirectory = project.directory().toAbsolutePath().normalize();
        BlockPos origin = LvcTrackingOverlayService.trackingOverlayOrigin(repositoryDirectory);
        ProjectSummarySnapshot snapshot = summarySnapshot(repositoryDirectory, origin);
        CachedProjectSummary cached = PROJECT_SUMMARY_CACHE.get(repositoryDirectory);

        if (cached != null && cached.snapshot().equals(snapshot))
        {
            return cached.summary();
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        LvcProjectSummary summary = new LvcProjectSummary(
                manifest.name(),
                LvcGitHistoryOps.countCommitsAcrossLocalBranches(repositoryDirectory),
                origin
        );

        PROJECT_SUMMARY_CACHE.put(repositoryDirectory, new CachedProjectSummary(snapshot, summary));
        return summary;
    }

    public static boolean isProjectRepository(Path candidate)
    {
        Objects.requireNonNull(candidate, "candidate");

        if (!Files.isDirectory(candidate.resolve(".git")) || !LvcSemanticRepository.isSemanticProject(candidate))
        {
            return false;
        }

        try (Git git = Git.open(candidate.toFile()))
        {
            git.getRepository();
            return true;
        }
        catch (Exception e)
        {
            LvcDiagnostics.debug("LvcProjectCatalog: rejected invalid LVC project repository '{}': {}", candidate, e.getMessage());
            return false;
        }
    }

    private static ProjectSummarySnapshot summarySnapshot(Path repositoryDirectory, @Nullable BlockPos origin)
            throws IOException, GitAPIException
    {
        long manifestModified = Files.getLastModifiedTime(repositoryDirectory.resolve(LvcSemanticRepository.MANIFEST)).toMillis();
        return new ProjectSummarySnapshot(
                manifestModified,
                origin,
                LvcGitHistoryOps.localBranchRefsSnapshot(repositoryDirectory)
        );
    }

    private static void deleteRecursively(Path directory) throws IOException
    {
        Files.walkFileTree(directory, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                deletePathWithRetries(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }

                deletePathWithRetries(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void closeTrackingOverlay(Path target)
    {
        try
        {
            LvcTrackingOverlayService.closeTrackingOverlay(target);
        }
        catch (RuntimeException | LinkageError e)
        {
            LvcDiagnostics.warn("LvcProjectCatalog: could not detach LVC tracking overlay before deleting '{}': {}",
                    target, e.toString());
        }
    }

    private static void deletePathWithRetries(Path path) throws IOException
    {
        IOException failure = null;

        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++)
        {
            makePathDeletable(path);

            try
            {
                Files.delete(path);

                if (attempt > 1)
                {
                    LvcDiagnostics.debug("LvcProjectCatalog: delete retry succeeded attempt={} path='{}'", attempt, path);
                }

                return;
            }
            catch (AccessDeniedException | DirectoryNotEmptyException e)
            {
                failure = e;
                LvcDiagnostics.debug("LvcProjectCatalog: delete retry needed attempt={} max={} path='{}' reason='{}'",
                        attempt, DELETE_RETRY_ATTEMPTS, path, e.toString());
                flushJGitFileCache(path);

                if (attempt < DELETE_RETRY_ATTEMPTS)
                {
                    sleepBeforeDeleteRetry(path);
                }
            }
        }

        throw failure != null ? failure : new IOException("Failed to delete " + path);
    }

    private static void makePathDeletable(Path path)
    {
        try
        {
            DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);

            if (dos != null)
            {
                dos.setReadOnly(false);
            }
        }
        catch (IOException | UnsupportedOperationException e)
        {
            LvcDiagnostics.debug("LvcProjectCatalog: failed to clear DOS readonly attr path='{}' reason='{}'", path, e.toString());
        }

        if (!path.toFile().setWritable(true, false))
        {
            LvcDiagnostics.debug("LvcProjectCatalog: failed to set writable attr path='{}'", path);
        }
    }

    private static void flushJGitFileCache(Path path)
    {
        try
        {
            new WindowCacheConfig().install();
            LvcDiagnostics.debug("LvcProjectCatalog: flushed JGit file cache before deleting '{}'", path);
        }
        catch (RuntimeException e)
        {
            LvcDiagnostics.debug("LvcProjectCatalog: failed to flush JGit file cache before deleting '{}': {}",
                    path, e.toString());
        }
    }

    private static void sleepBeforeDeleteRetry(Path path) throws IOException
    {
        try
        {
            Thread.sleep(DELETE_RETRY_DELAY_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying LVC project delete: " + path, e);
        }
    }

    private record ProjectSummarySnapshot(long manifestModified, @Nullable BlockPos origin,
                                          Map<String, String> localBranchRefs)
    {
    }

    private record CachedProjectSummary(ProjectSummarySnapshot snapshot, LvcProjectSummary summary)
    {
    }
}
