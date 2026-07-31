package me.arnavpmr.lvc.semantic;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.arnavpmr.lvc.capture.LvcCaptureEngine;

public record LvcSemanticScanResult(String siteId, int unchangedChunks, int changedChunks, int addedChunks,
                                    int removedChunks, int unknownChunks, List<LvcSemanticScanMismatch> samples)
{
    public LvcSemanticScanResult(String siteId, int unchangedChunks, int changedChunks, int addedChunks,
                                 int removedChunks, int unknownChunks)
    {
        this(siteId, unchangedChunks, changedChunks, addedChunks, removedChunks, unknownChunks, List.of());
    }

    public LvcSemanticScanResult
    {
        samples = List.copyOf(samples);
    }

    public static LvcSemanticScanResult compare(String siteId, Map<String, String> expectedTrackedHashes,
                                                LvcCaptureEngine.Result scan)
    {
        Set<String> keys = new HashSet<>();
        keys.addAll(expectedTrackedHashes.keySet());
        keys.addAll(scan.trackedHashes().keySet());
        keys.addAll(scan.unknownChunks());

        int unchanged = 0;
        int changed = 0;
        int added = 0;
        int removed = 0;
        int unknown = 0;

        for (String key : keys)
        {
            if (scan.unknownChunks().contains(key))
            {
                unknown++;
                continue;
            }

            String expected = expectedTrackedHashes.get(key);
            String actual = scan.trackedHashes().get(key);

            if (expected == null && actual != null)
            {
                added++;
            }
            else if (expected != null && actual == null)
            {
                removed++;
            }
            else if (Objects.equals(expected, actual))
            {
                unchanged++;
            }
            else
            {
                changed++;
            }
        }

        return new LvcSemanticScanResult(siteId, unchanged, changed, added, removed, unknown);
    }

    public boolean clean()
    {
        return this.dirtyChunks() == 0 && this.unknownChunks == 0;
    }

    public int dirtyChunks()
    {
        return this.changedChunks + this.addedChunks + this.removedChunks;
    }

    public int knownChunks()
    {
        return this.unchangedChunks + this.changedChunks + this.addedChunks + this.removedChunks;
    }

    public LvcSemanticScanResult withSamples(List<LvcSemanticScanMismatch> samples)
    {
        return new LvcSemanticScanResult(this.siteId, this.unchangedChunks, this.changedChunks, this.addedChunks,
                this.removedChunks, this.unknownChunks, samples);
    }
}
