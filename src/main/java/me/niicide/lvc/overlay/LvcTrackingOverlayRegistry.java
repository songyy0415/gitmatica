package me.niicide.lvc.overlay;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

final class LvcTrackingOverlayRegistry
{
    private static final Map<Path, LvcTrackingOverlayEntry> ENTRIES = new HashMap<>();
    private static final Map<Path, BlockPos> ORIGINS = new HashMap<>();

    private LvcTrackingOverlayRegistry()
    {
    }

    @Nullable
    static LvcTrackingOverlayEntry entry(Path repositoryDirectory)
    {
        return ENTRIES.get(key(repositoryDirectory));
    }

    static void put(Path repositoryDirectory, LvcTrackingOverlayEntry entry)
    {
        ENTRIES.put(key(repositoryDirectory), entry);
    }

    @Nullable
    static LvcTrackingOverlayEntry remove(Path repositoryDirectory)
    {
        return ENTRIES.remove(key(repositoryDirectory));
    }

    @Nullable
    static BlockPos origin(Path repositoryDirectory)
    {
        return ORIGINS.get(key(repositoryDirectory));
    }

    static void putOrigin(Path repositoryDirectory, BlockPos origin)
    {
        ORIGINS.put(key(repositoryDirectory), origin.immutable());
    }

    static void removeOrigin(Path repositoryDirectory)
    {
        ORIGINS.remove(key(repositoryDirectory));
    }

    static Path key(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize();
    }
}
