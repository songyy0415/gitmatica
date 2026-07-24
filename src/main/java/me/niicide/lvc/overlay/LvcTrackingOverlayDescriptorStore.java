package me.niicide.lvc.overlay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.storage.LvcAtomicFiles;

final class LvcTrackingOverlayDescriptorStore
{
    static final String CACHE_DIRECTORY = "lvc-cache";
    private static final String DESCRIPTOR_FILE = "tracking-overlay.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LvcTrackingOverlayDescriptorStore()
    {
    }

    static boolean write(Path repositoryDirectory, LvcTrackingOverlayDescriptor descriptor)
    {
        try
        {
            LvcAtomicFiles.writeUtf8(path(repositoryDirectory), GSON.toJson(descriptor));
            return true;
        }
        catch (IOException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayDescriptorStore: failed to write descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return false;
        }
    }

    @Nullable
    static LvcTrackingOverlayDescriptor read(Path repositoryDirectory)
    {
        Path path = path(repositoryDirectory);

        if (!Files.isRegularFile(path))
        {
            return null;
        }

        try
        {
            return GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8),
                    LvcTrackingOverlayDescriptor.class
            );
        }
        catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException e)
        {
            LvcDiagnostics.debug("LvcTrackingOverlayDescriptorStore: ignored invalid descriptor repo='{}' error='{}'",
                    repositoryDirectory, e.getMessage());
            return null;
        }
    }

    static Path path(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(CACHE_DIRECTORY)
                .resolve(DESCRIPTOR_FILE)
                .normalize();
    }
}
