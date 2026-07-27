package me.niicide.lvc.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.capture.LvcCapturePlanner;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.util.LvcEntityNbt;

final class LvcRegionMergeEngine
{
    private static final int CHUNK_SIZE = LvcChunk.DEFAULT_SIZE;
    private static final LvcMergeBlockPayload AIR_PAYLOAD =
            new LvcMergeBlockPayload("minecraft:air", null);

    private LvcRegionMergeEngine()
    {
    }

    static LvcRegionMergeResult merge(Path repositoryDirectory, Repository repository,
                                      RevCommit baseCommit, RevCommit currentCommit,
                                      RevCommit sourceCommit, LvcManifest.Site metadataSite,
                                      LvcManifest.Site baseSite, LvcManifest.Site currentSite,
                                      LvcManifest.Site sourceSite,
                                      @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws IOException
    {
        Side base = Side.read(repository, baseCommit, baseSite);
        Side current = Side.read(repository, currentCommit, currentSite);
        Side source = Side.read(repository, sourceCommit, sourceSite);
        Map<String, SelectedRegion> selectedRegions =
                mergeRegionDefinitions(base, current, source, resolution);
        List<LvcManifest.Region> mergedRegions = selectedRegions.values().stream()
                .map(SelectedRegion::region)
                .filter(Objects::nonNull)
                .toList();
        LvcManifest.Site mergedSite = new LvcManifest.Site(
                metadataSite.id(),
                metadataSite.name(),
                metadataSite.dimension(),
                mergedRegions,
                metadataSite.hashIndex(),
                Map.of(),
                Map.of()
        );

        return mergeTrackedContents(
                repositoryDirectory, mergedSite, base, current, source, resolution);
    }

    private static Map<String, SelectedRegion> mergeRegionDefinitions(
            Side base, Side current, Side source,
            @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws LvcMergeConflictException
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(current.regions().keySet());
        names.addAll(source.regions().keySet());
        names.addAll(base.regions().keySet());
        Map<String, SelectedRegion> selected = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();

        for (String name : names)
        {
            LvcManifest.Region baseRegion = base.regions().get(name);
            LvcManifest.Region currentRegion = current.regions().get(name);
            LvcManifest.Region sourceRegion = source.regions().get(name);
            LvcManifest.Region selectedRegion;

            if (sameDefinition(currentRegion, sourceRegion))
            {
                selectedRegion = currentRegion;
            }
            else if (sameDefinition(currentRegion, baseRegion))
            {
                selectedRegion = sourceRegion;
            }
            else if (sameDefinition(sourceRegion, baseRegion))
            {
                selectedRegion = currentRegion;
            }
            else
            {
                conflicts.add(name);

                if (resolution == null)
                {
                    continue;
                }

                selectedRegion = regionForResolution(
                        resolution, baseRegion, currentRegion, sourceRegion);
            }

            selected.put(name, new SelectedRegion(selectedRegion));
        }

        if (!conflicts.isEmpty() && resolution == null)
        {
            throw new LvcMergeConflictException(
                    LvcMergeConflictException.Reason.SUBREGION,
                    "LVC sub-region definition conflicts: " + String.join(", ", conflicts)
            );
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(selected));
    }

    @Nullable
    private static LvcManifest.Region regionForResolution(
            LvcProjectService.BranchMergeConflictResolution resolution,
            @Nullable LvcManifest.Region base,
            @Nullable LvcManifest.Region current,
            @Nullable LvcManifest.Region source)
    {
        return switch (resolution)
        {
            case BASE -> base;
            case INCOMING -> source;
            case YOURS -> current;
        };
    }

    private static boolean sameDefinition(@Nullable LvcManifest.Region left,
                                          @Nullable LvcManifest.Region right)
    {
        if (left == null || right == null)
        {
            return left == right;
        }

        return Objects.equals(left.min(), right.min()) &&
                Objects.equals(left.size(), right.size());
    }

    private static LvcRegionMergeResult mergeTrackedContents(
            Path repositoryDirectory, LvcManifest.Site mergedSite,
            Side base, Side current, Side source,
            @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws IOException
    {
        Map<LvcChunkCoordinate, BitSet> mergedMasks = LvcCapturePlanner.planSite(mergedSite);
        validateMergedPayloads(
                mergedMasks, mergedSite.regions(), base, current, source, resolution);
        TreeMap<String, String> fullHashes = new TreeMap<>();
        TreeMap<String, String> trackedHashes = new TreeMap<>();

        for (Map.Entry<LvcChunkCoordinate, BitSet> entry : mergedMasks.entrySet())
        {
            String chunkKey = entry.getKey().key();
            BitSet mask = entry.getValue();
            ChunkContent baseChunk = base.chunkContent(chunkKey);
            ChunkContent currentChunk = current.chunkContent(chunkKey);
            ChunkContent sourceChunk = source.chunkContent(chunkKey);
            List<String> states = new ArrayList<>(mask.cardinality());
            List<LvcChunk.BlockEntityRecord> blockEntities = new ArrayList<>();

            for (int maskIndex = mask.nextSetBit(0);
                 maskIndex >= 0;
                 maskIndex = mask.nextSetBit(maskIndex + 1))
            {
                LvcMergeBlockPayload payload = choosePayload(
                        chunkKey,
                        maskIndex,
                        baseChunk.payloads().get(maskIndex),
                        currentChunk.payloads().get(maskIndex),
                        sourceChunk.payloads().get(maskIndex),
                        resolution
                );
                states.add(payload.blockState());

                if (payload.blockEntityNbt() != null)
                {
                    blockEntities.add(
                            new LvcChunk.BlockEntityRecord(maskIndex, payload.blockEntityNbt()));
                }
            }

            List<LvcChunk.EntityRecord> entities = chooseEntityPayload(
                    chunkKey,
                    mergedSite.regions(),
                    baseChunk.entities(),
                    currentChunk.entities(),
                    sourceChunk.entities(),
                    resolution
            );
            LvcChunk mergedChunk = LvcChunk.fromTrackedContent(
                    CHUNK_SIZE,
                    CHUNK_SIZE,
                    CHUNK_SIZE,
                    mask,
                    states,
                    blockEntities,
                    entities
            );
            byte[] hashContentBytes = LvcChunkCodec.encodeHashContent(mergedChunk);
            String fullHash = LvcChunkStore.objectId(hashContentBytes);
            String trackedHash =
                    LvcChunkStore.objectId(LvcChunkCodec.encodeTrackedContent(mergedChunk));
            LvcChunkStore.writeObjectIfMissing(
                    repositoryDirectory,
                    fullHash,
                    LvcChunkCodec.encodeStorageBytes(hashContentBytes)
            );
            fullHashes.put(chunkKey, fullHash);
            trackedHashes.put(chunkKey, trackedHash);
        }

        LvcDiagnostics.debug(
                "LvcRegionMergeEngine: merged definitions then payloads site='{}' regions={} chunks={}",
                mergedSite.id(), mergedSite.regions().size(), mergedMasks.size());
        return new LvcRegionMergeResult(
                mergedSite.withHashRefs(Map.copyOf(fullHashes), Map.copyOf(trackedHashes)),
                mergedMasks.size()
        );
    }

    private static void validateMergedPayloads(
            Map<LvcChunkCoordinate, BitSet> mergedMasks,
            List<LvcManifest.Region> mergedRegions,
            Side base, Side current, Side source,
            @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws IOException
    {
        for (Map.Entry<LvcChunkCoordinate, BitSet> entry : mergedMasks.entrySet())
        {
            String chunkKey = entry.getKey().key();
            ChunkContent baseChunk = base.chunkContent(chunkKey);
            ChunkContent currentChunk = current.chunkContent(chunkKey);
            ChunkContent sourceChunk = source.chunkContent(chunkKey);
            BitSet mask = entry.getValue();

            for (int maskIndex = mask.nextSetBit(0);
                 maskIndex >= 0;
                 maskIndex = mask.nextSetBit(maskIndex + 1))
            {
                choosePayload(
                        chunkKey,
                        maskIndex,
                        baseChunk.payloads().get(maskIndex),
                        currentChunk.payloads().get(maskIndex),
                        sourceChunk.payloads().get(maskIndex),
                        resolution
                );
            }

            chooseEntityPayload(
                    chunkKey,
                    mergedRegions,
                    baseChunk.entities(),
                    currentChunk.entities(),
                    sourceChunk.entities(),
                    resolution
            );
        }
    }

    private static LvcMergeBlockPayload choosePayload(
            String chunkKey,
            int maskIndex,
            @Nullable LvcMergeBlockPayload base,
            @Nullable LvcMergeBlockPayload current,
            @Nullable LvcMergeBlockPayload source,
            @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws LvcMergeConflictException
    {
        if (trackedPayloadEquals(current, source))
        {
            return materialize(chooseEquivalentPayload(base, current, source));
        }

        if (trackedPayloadEquals(current, base))
        {
            return materialize(source);
        }

        if (trackedPayloadEquals(source, base))
        {
            return materialize(current);
        }

        if (resolution == null)
        {
            LvcDiagnostics.debug(
                    "LvcRegionMergeEngine: block payload conflict chunk='{}' index={} baseState='{}' currentState='{}' sourceState='{}' baseBE={} currentBE={} sourceBE={}",
                    chunkKey,
                    maskIndex,
                    canonicalState(base),
                    canonicalState(current),
                    canonicalState(source),
                    hasBlockEntity(base),
                    hasBlockEntity(current),
                    hasBlockEntity(source)
            );
            throw new LvcMergeConflictException(
                    LvcMergeConflictException.Reason.BLOCK_PAYLOAD,
                    "LVC block conflict at " + chunkKey + " index " + maskIndex
            );
        }

        return materialize(switch (resolution)
        {
            case BASE -> base;
            case INCOMING -> source;
            case YOURS -> current;
        });
    }

    @Nullable
    private static LvcMergeBlockPayload chooseEquivalentPayload(
            @Nullable LvcMergeBlockPayload base,
            @Nullable LvcMergeBlockPayload current,
            @Nullable LvcMergeBlockPayload source)
    {
        if (current == null || source == null || fullPayloadEquals(current, source))
        {
            return current;
        }

        if (base != null && fullPayloadEquals(current, base))
        {
            return source;
        }

        return current;
    }

    private static LvcMergeBlockPayload materialize(@Nullable LvcMergeBlockPayload payload)
    {
        return payload != null ? payload : AIR_PAYLOAD;
    }

    private static boolean trackedPayloadEquals(
            @Nullable LvcMergeBlockPayload left,
            @Nullable LvcMergeBlockPayload right)
    {
        if (left == null || right == null)
        {
            return left == right;
        }

        return Objects.equals(canonicalState(left), canonicalState(right)) &&
                Arrays.equals(left.blockEntityNbt(), right.blockEntityNbt());
    }

    private static boolean fullPayloadEquals(
            LvcMergeBlockPayload left, LvcMergeBlockPayload right)
    {
        return Objects.equals(left.blockState(), right.blockState()) &&
                Arrays.equals(left.blockEntityNbt(), right.blockEntityNbt());
    }

    @Nullable
    private static String canonicalState(@Nullable LvcMergeBlockPayload payload)
    {
        return payload == null ? null :
                LvcChunkCodec.canonicalTrackedBlockState(payload.blockState());
    }

    private static boolean hasBlockEntity(@Nullable LvcMergeBlockPayload payload)
    {
        return payload != null && payload.blockEntityNbt() != null;
    }

    private static List<LvcChunk.EntityRecord> chooseEntityPayload(
            String chunkKey,
            List<LvcManifest.Region> mergedRegions,
            List<LvcChunk.EntityRecord> baseEntities,
            List<LvcChunk.EntityRecord> currentEntities,
            List<LvcChunk.EntityRecord> sourceEntities,
            @Nullable LvcProjectService.BranchMergeConflictResolution resolution)
            throws IOException
    {
        List<LvcChunk.EntityRecord> base = entitiesWithinRegions(baseEntities, mergedRegions);
        List<LvcChunk.EntityRecord> current =
                entitiesWithinRegions(currentEntities, mergedRegions);
        List<LvcChunk.EntityRecord> source =
                entitiesWithinRegions(sourceEntities, mergedRegions);

        if (entityPayloadEquals(current, source))
        {
            return current;
        }

        if (entityPayloadEquals(current, base))
        {
            return source;
        }

        if (entityPayloadEquals(source, base))
        {
            return current;
        }

        if (resolution != null)
        {
            return switch (resolution)
            {
                case BASE -> base;
                case INCOMING -> source;
                case YOURS -> current;
            };
        }

        LvcDiagnostics.debug(
                "LvcRegionMergeEngine: hidden entity payload changed on both sides chunk='{}'; kept current side entities={}",
                chunkKey, current.size());
        return current;
    }

    private static List<LvcChunk.EntityRecord> entitiesWithinRegions(
            List<LvcChunk.EntityRecord> entities,
            List<LvcManifest.Region> regions) throws IOException
    {
        List<LvcChunk.EntityRecord> filtered = new ArrayList<>();

        for (LvcChunk.EntityRecord entity : entities)
        {
            Vec3 position = LvcEntityNbt.position(entity.canonicalNbt());

            if (regions.stream().anyMatch(region -> contains(region, position)))
            {
                filtered.add(entity);
            }
        }

        return List.copyOf(filtered);
    }

    private static boolean entityPayloadEquals(
            List<LvcChunk.EntityRecord> left,
            List<LvcChunk.EntityRecord> right)
    {
        if (left.size() != right.size())
        {
            return false;
        }

        for (int index = 0; index < left.size(); index++)
        {
            if (!Arrays.equals(
                    left.get(index).canonicalNbt(),
                    right.get(index).canonicalNbt()))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean contains(LvcManifest.Region region, Vec3 position)
    {
        LvcIntPosition min = LvcIntPosition.fromList(region.min());
        LvcIntPosition size = LvcIntPosition.fromList(region.size());
        return position.x >= min.x() && position.x < (long) min.x() + size.x() &&
                position.y >= min.y() && position.y < (long) min.y() + size.y() &&
                position.z >= min.z() && position.z < (long) min.z() + size.z();
    }

    private record SelectedRegion(@Nullable LvcManifest.Region region)
    {
    }

    private record Side(Repository repository, RevCommit commit, LvcManifest.Site site,
                        Map<String, LvcManifest.Region> regions,
                        Map<String, ChunkContent> chunkCache)
    {
        private static Side read(
                Repository repository, RevCommit commit, LvcManifest.Site site)
                throws IOException
        {
            Map<String, LvcManifest.Region> regions = new LinkedHashMap<>();

            for (LvcManifest.Region region : site.regions())
            {
                if (regions.put(region.name(), region) != null)
                {
                    throw new IOException(
                            "LVC sub-region names must be unique for merging: " +
                                    region.name());
                }
            }

            return new Side(
                    repository,
                    commit,
                    site,
                    Collections.unmodifiableMap(new LinkedHashMap<>(regions)),
                    new HashMap<>()
            );
        }

        private ChunkContent chunkContent(String chunkKey) throws IOException
        {
            ChunkContent cached = this.chunkCache.get(chunkKey);

            if (cached != null)
            {
                return cached;
            }

            String fullHash = this.site.fullHashes().get(chunkKey);

            if (fullHash == null)
            {
                this.chunkCache.put(chunkKey, ChunkContent.EMPTY);
                return ChunkContent.EMPTY;
            }

            LvcChunk chunk =
                    LvcMergeObjectResolver.readChunk(this.repository, this.commit, fullHash);

            if (chunk.sizeX() != CHUNK_SIZE ||
                    chunk.sizeY() != CHUNK_SIZE ||
                    chunk.sizeZ() != CHUNK_SIZE)
            {
                throw new IOException(
                        "Commit " + this.commit.getName() +
                                " has an unsupported LVC chunk shape at " + chunkKey);
            }

            ChunkContent content =
                    new ChunkContent(payloadsByMaskIndex(chunk), chunk.entities());
            this.chunkCache.put(chunkKey, content);
            return content;
        }
    }

    private record ChunkContent(Map<Integer, LvcMergeBlockPayload> payloads,
                                List<LvcChunk.EntityRecord> entities)
    {
        private static final ChunkContent EMPTY =
                new ChunkContent(Map.of(), List.of());
    }

    private static Map<Integer, LvcMergeBlockPayload> payloadsByMaskIndex(LvcChunk chunk)
    {
        Map<Integer, byte[]> blockEntities = new HashMap<>();
        Map<Integer, LvcMergeBlockPayload> payloads = new HashMap<>();
        BitSet mask = chunk.trackedMask();
        int ordinal = 0;

        for (LvcChunk.BlockEntityRecord record : chunk.blockEntities())
        {
            blockEntities.put(record.index(), record.canonicalNbt());
        }

        for (int maskIndex = mask.nextSetBit(0);
             maskIndex >= 0;
             maskIndex = mask.nextSetBit(maskIndex + 1))
        {
            payloads.put(maskIndex, new LvcMergeBlockPayload(
                    chunk.blockStateAtTrackedOrdinal(ordinal),
                    blockEntities.get(maskIndex)
            ));
            ordinal++;
        }

        return Map.copyOf(payloads);
    }
}

record LvcRegionMergeResult(LvcManifest.Site site, int mergedChunks)
{
}
