package me.arnavpmr.lvc.overlay;

import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nullable;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

record LvcTrackingOverlayDescriptor(String commitId, String siteId, String dimension,
                                    String cacheFile, String overlayName, @Nullable String revision)
{
    boolean matches(String commitId, String siteId, String dimension, Path cacheFile, String overlayName)
    {
        Path descriptorCacheFile = this.cacheFilePath();
        return Objects.equals(this.commitId, commitId) &&
                Objects.equals(this.siteId, siteId) &&
                Objects.equals(this.dimension, dimension) &&
                descriptorCacheFile != null &&
                pathsEqual(cacheFile, descriptorCacheFile) &&
                Objects.equals(this.overlayName, overlayName);
    }

    boolean matchesPlacement(Path repositoryDirectory, SchematicPlacement placement)
    {
        Path descriptorCacheFile = this.cacheFilePath();
        return descriptorCacheFile != null &&
                descriptorCacheFile.startsWith(cacheDirectory(repositoryDirectory)) &&
                Objects.equals(this.overlayName, placement.getName()) &&
                pathsEqual(descriptorCacheFile, placement.getSchematicFile());
    }

    @Nullable
    Path cacheFilePath()
    {
        return this.cacheFile == null ? null : Path.of(this.cacheFile).toAbsolutePath().normalize();
    }

    private static Path cacheDirectory(Path repositoryDirectory)
    {
        return repositoryDirectory.toAbsolutePath().normalize()
                .resolve(".git")
                .resolve(LvcTrackingOverlayDescriptorStore.CACHE_DIRECTORY)
                .normalize();
    }

    private static boolean pathsEqual(Path expected, @Nullable Path actual)
    {
        return actual != null &&
                expected.toAbsolutePath().normalize().equals(actual.toAbsolutePath().normalize());
    }
}
