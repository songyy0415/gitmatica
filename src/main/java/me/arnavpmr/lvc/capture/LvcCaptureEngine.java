package me.arnavpmr.lvc.capture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import me.arnavpmr.lvc.model.LvcManifest;
import me.arnavpmr.lvc.model.LvcSitePlacement;
import me.arnavpmr.lvc.storage.LvcChunkStore;

public final class LvcCaptureEngine
{
    private LvcCaptureEngine()
    {
    }

    public static Result captureSite(Path repositoryDirectory, LvcManifest.Site site, LvcSitePlacement placement,
                                     LvcWorldReader worldReader) throws IOException
    {
        return captureSite(site, placement, worldReader, (objectId, bytes) -> LvcChunkStore.writeObjectIfMissing(repositoryDirectory, objectId, bytes), false, true);
    }

    public static Result scanSite(LvcManifest.Site site, LvcSitePlacement placement,
                                  LvcWorldReader worldReader) throws IOException
    {
        return captureSite(site, placement, worldReader, null, true, false);
    }

    private static Result captureSite(LvcManifest.Site site, LvcSitePlacement placement,
                                      LvcWorldReader worldReader, LvcCaptureSession.ObjectIdResolver objectIdResolver,
                                      boolean allowUnknownChunks, boolean computeFullHashes) throws IOException
    {
        LvcCaptureSession session = new LvcCaptureSession(
                LvcSiteWorkPlan.create(site, placement),
                worldReader,
                objectIdResolver,
                allowUnknownChunks,
                computeFullHashes
        );

        while (!session.isComplete())
        {
            session.processNextChunk();
        }

        return session.result();
    }

    public record Result(Map<String, String> fullHashes, Map<String, String> trackedHashes, Set<String> unknownChunks)
    {
        public Result
        {
            fullHashes = Map.copyOf(fullHashes);
            trackedHashes = Map.copyOf(trackedHashes);
            unknownChunks = Set.copyOf(unknownChunks);

            if (!fullHashes.isEmpty() && !fullHashes.keySet().equals(trackedHashes.keySet()))
            {
                throw new IllegalArgumentException("LVC capture full hashes and tracked hashes must have matching keys");
            }
        }

        public Result(Map<String, String> fullHashes)
        {
            this(fullHashes, fullHashes, Set.of());
        }

        public Result(Map<String, String> fullHashes, Set<String> unknownChunks)
        {
            this(fullHashes, fullHashes, unknownChunks);
        }
    }
}
