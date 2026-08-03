package me.arnavpmr.lvc.model;

import java.util.Objects;

public final class LvcContentFormat
{
    public static final String CHUNK_FORMAT = "lvcchunk-raw-v1";
    public static final String HASH_INDEX_FORMAT = "lvchash-index-raw-v1";
    public static final String HASH_ALGORITHM = "sha256";
    public static final String HASH_INDEX_DIRECTORY = "indexes";
    public static final String HASH_INDEX_EXTENSION = ".lvcidx";

    private LvcContentFormat()
    {
    }

    public static String defaultHashIndexPath(String siteId)
    {
        String cleanSiteId = Objects.requireNonNull(siteId, "siteId").trim();

        if (!cleanSiteId.matches("[A-Za-z0-9._-]+"))
        {
            throw new IllegalArgumentException("LVC site id is not safe for an index file name: " + siteId);
        }

        return HASH_INDEX_DIRECTORY + "/" + cleanSiteId + HASH_INDEX_EXTENSION;
    }
}
