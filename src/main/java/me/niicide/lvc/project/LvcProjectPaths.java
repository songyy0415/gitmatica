package me.niicide.lvc.project;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class LvcProjectPaths
{
    public static final String REPOS_DIRECTORY = "gitmatica-projects";

    private LvcProjectPaths()
    {
    }

    public static Path reposDirectory(Path gameRunDirectory)
    {
        return Objects.requireNonNull(gameRunDirectory, "gameRunDirectory")
                .resolve(REPOS_DIRECTORY)
                .normalize();
    }

    public static Path repositoryDirectory(Path gameRunDirectory, String projectName)
    {
        String displayName = normalizeDisplayName(projectName);
        validateProjectDirectoryName(displayName);

        Path reposRoot = reposDirectory(gameRunDirectory).toAbsolutePath().normalize();
        Path target = reposRoot.resolve(displayName).normalize();

        if (!target.startsWith(reposRoot) || !reposRoot.equals(target.getParent()))
        {
            throw new IllegalArgumentException("LVC repository name must resolve directly under " + reposRoot + ": " + displayName);
        }

        return reposDirectory(gameRunDirectory).resolve(displayName).normalize();
    }

    public static Path minecraftDisplayPath(Path gameRunDirectory, Path path)
    {
        Path gameDirectory = Objects.requireNonNull(gameRunDirectory, "gameRunDirectory")
                .toAbsolutePath()
                .normalize();
        Path target = Objects.requireNonNull(path, "path")
                .toAbsolutePath()
                .normalize();

        if (!target.startsWith(gameDirectory))
        {
            throw new IllegalArgumentException("Path must be inside the Minecraft game directory: " + target);
        }

        return Path.of(".minecraft").resolve(gameDirectory.relativize(target));
    }

    public static String normalizeDisplayName(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            return "lvc-project-" + Instant.now().toEpochMilli();
        }

        return projectName.trim();
    }

    private static void validateProjectDirectoryName(String displayName)
    {
        if (displayName.isBlank())
        {
            throw new IllegalArgumentException("LVC repository name must not be blank");
        }

        if (".".equals(displayName) || "..".equals(displayName))
        {
            throw new IllegalArgumentException("LVC repository name must not be a path element: " + displayName);
        }

        if (displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0)
        {
            throw new IllegalArgumentException("LVC repository name must not contain path separators: " + displayName);
        }

        for (int i = 0; i < displayName.length(); i++)
        {
            if (Character.isISOControl(displayName.charAt(i)))
            {
                throw new IllegalArgumentException("LVC repository name must not contain control characters");
            }
        }

        if (Path.of(displayName).isAbsolute())
        {
            throw new IllegalArgumentException("LVC repository name must not be an absolute path: " + displayName);
        }
    }
}
