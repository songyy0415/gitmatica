package me.arnavpmr.lvc.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.arnavpmr.lvc.LvcDiagnostics;
import me.arnavpmr.lvc.storage.LvcAtomicFiles;

public final class LvcRefreshMarker
{
    public static final String MARKER_FILE = "lvc-refresh-needed.json";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LvcRefreshMarker()
    {
    }

    public static Entry write(Path repositoryDirectory, String reason, @Nullable String targetCommit) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(reason, "reason");

        Entry entry = new Entry(reason, targetCommit, Instant.now().toString());
        Path path = markerPath(repositoryDirectory);
        Files.createDirectories(path.getParent());
        writeAtomic(path, GSON.toJson(entry));
        LvcDiagnostics.debug("LVC refresh marker written repo='{}' reason='{}' targetCommit='{}'",
                repositoryDirectory, reason, targetCommit);
        return entry;
    }

    public static boolean exists(Path repositoryDirectory)
    {
        return Files.isRegularFile(markerPath(repositoryDirectory));
    }

    @Nullable
    public static Entry read(Path repositoryDirectory) throws IOException
    {
        Path path = markerPath(repositoryDirectory);

        if (!Files.isRegularFile(path))
        {
            return null;
        }

        try
        {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Entry.class);
        }
        catch (JsonParseException | IllegalArgumentException | NullPointerException e)
        {
            throw new IOException("Invalid LVC refresh marker: " + path, e);
        }
    }

    public static void delete(Path repositoryDirectory) throws IOException
    {
        Path path = markerPath(repositoryDirectory);
        Files.deleteIfExists(path);
        Files.deleteIfExists(tempPath(path));
        LvcDiagnostics.debug("LVC refresh marker cleared repo='{}'", repositoryDirectory);
    }

    private static Path markerPath(Path repositoryDirectory)
    {
        return LvcOperationJournal.gitLocalDirectory(repositoryDirectory).resolve(MARKER_FILE);
    }

    private static void writeAtomic(Path path, String json) throws IOException
    {
        LvcAtomicFiles.writeUtf8(path, tempPath(path), json);
    }

    private static Path tempPath(Path path)
    {
        return path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
    }

    public record Entry(String reason, @Nullable String targetCommit, String createdAt)
    {
    }
}
