package me.arnavpmr.lvc.task;

import me.arnavpmr.lvc.git.LvcGitTreeReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import me.arnavpmr.lvc.model.LvcChunk;
import me.arnavpmr.lvc.storage.LvcChunkCodec;
import me.arnavpmr.lvc.storage.LvcChunkStore;

final class LvcCommitChunkCache
{
    private static final int DEFAULT_MAX_COMMIT_ENTRIES = 4096;
    private static final int DEFAULT_MAX_OBJECT_ENTRIES = 4096;

    private final Repository repository;
    private final Map<CommitObjectKey, LvcChunk> chunksByCommitObject;
    private final Map<String, LvcChunk> chunksByObjectId;
    private int commitHits;
    private int objectHits;
    private int misses;

    LvcCommitChunkCache(Repository repository)
    {
        this(repository, DEFAULT_MAX_COMMIT_ENTRIES, DEFAULT_MAX_OBJECT_ENTRIES);
    }

    private LvcCommitChunkCache(Repository repository, int maxCommitEntries, int maxObjectEntries)
    {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.chunksByCommitObject = lruMap(maxCommitEntries);
        this.chunksByObjectId = lruMap(maxObjectEntries);
    }

    LvcChunk read(RevCommit commit, String objectId) throws IOException
    {
        CommitObjectKey key = new CommitObjectKey(commit.getName(), objectId);
        LvcChunk chunk = this.chunksByCommitObject.get(key);

        if (chunk != null)
        {
            this.commitHits++;
            return chunk;
        }

        byte[] bytes = LvcGitTreeReader.readCommitFile(this.repository, commit, LvcChunkStore.objectRepositoryPath(objectId));

        if (bytes == null)
        {
            throw new IOException("Commit " + commit.getName() + " is missing LVC object: " + objectId);
        }

        chunk = this.chunksByObjectId.get(objectId);

        if (chunk != null)
        {
            this.objectHits++;
        }
        else
        {
            chunk = LvcChunkCodec.decode(bytes);
            this.chunksByObjectId.put(objectId, chunk);
            this.misses++;
        }

        this.chunksByCommitObject.put(key, chunk);
        return chunk;
    }

    Stats stats()
    {
        return new Stats(this.commitHits, this.objectHits, this.misses,
                this.chunksByCommitObject.size(), this.chunksByObjectId.size());
    }

    private static <K, V> Map<K, V> lruMap(int maxEntries)
    {
        return new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75F, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
            {
                return this.size() > maxEntries;
            }
        };
    }

    record Stats(int commitHits, int objectHits, int misses, int commitEntries, int objectEntries)
    {
    }

    private record CommitObjectKey(String commitId, String objectId)
    {
    }
}
