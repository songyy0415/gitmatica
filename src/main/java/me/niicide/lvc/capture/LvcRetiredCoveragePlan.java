package me.niicide.lvc.capture;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;

/**
 * Exact project-relative coverage that existed in one site definition but is not
 * covered by any sub-region in the updated definition.
 */
public final class LvcRetiredCoveragePlan
{
    private static final int MAX_FILL_VOLUME = 32_768;
    private static final LvcRetiredCoveragePlan EMPTY = new LvcRetiredCoveragePlan(List.of(), 0);

    private final List<LvcSiteWorkPlan.ChunkWork> chunks;
    private final int blockCount;

    private LvcRetiredCoveragePlan(List<LvcSiteWorkPlan.ChunkWork> chunks, int blockCount)
    {
        this.chunks = List.copyOf(chunks);
        this.blockCount = blockCount;
    }

    public static LvcRetiredCoveragePlan empty()
    {
        return EMPTY;
    }

    public static LvcRetiredCoveragePlan between(LvcManifest.Site previousSite, LvcManifest.Site updatedSite)
    {
        Objects.requireNonNull(previousSite, "previousSite");
        Objects.requireNonNull(updatedSite, "updatedSite");

        Map<LvcChunkCoordinate, BitSet> previousCoverage = LvcCapturePlanner.planSite(previousSite);
        Map<LvcChunkCoordinate, BitSet> updatedCoverage = LvcCapturePlanner.planSite(updatedSite);
        List<LvcSiteWorkPlan.ChunkWork> chunks = new ArrayList<>();
        int blockCount = 0;

        for (Map.Entry<LvcChunkCoordinate, BitSet> entry : previousCoverage.entrySet())
        {
            BitSet retired = (BitSet) entry.getValue().clone();
            BitSet retained = updatedCoverage.get(entry.getKey());

            if (retained != null)
            {
                retired.andNot(retained);
            }

            if (!retired.isEmpty())
            {
                chunks.add(new LvcSiteWorkPlan.ChunkWork(entry.getKey(), retired));
                blockCount += retired.cardinality();
            }
        }

        return chunks.isEmpty() ? EMPTY : new LvcRetiredCoveragePlan(chunks, blockCount);
    }

    public boolean isEmpty()
    {
        return this.chunks.isEmpty();
    }

    public List<LvcSiteWorkPlan.ChunkWork> chunks()
    {
        return this.chunks;
    }

    public int chunkCount()
    {
        return this.chunks.size();
    }

    public int blockCount()
    {
        return this.blockCount;
    }

    public List<Cuboid> cuboids()
    {
        List<Cuboid> cuboids = new ArrayList<>();

        for (LvcSiteWorkPlan.ChunkWork work : this.chunks)
        {
            appendChunkCuboids(work, cuboids);
        }

        return coalesceCuboids(cuboids);
    }

    private static List<Cuboid> coalesceCuboids(List<Cuboid> cuboids)
    {
        List<Cuboid> current = sortCuboids(cuboids);
        int previousSize;

        do
        {
            previousSize = current.size();

            for (Axis axis : Axis.values())
            {
                current = mergeAlongAxis(current, axis);
            }
        }
        while (current.size() < previousSize);

        return List.copyOf(current);
    }

    private static List<Cuboid> mergeAlongAxis(List<Cuboid> cuboids, Axis axis)
    {
        Map<MergeKey, List<Cuboid>> groups = new HashMap<>();

        for (Cuboid cuboid : cuboids)
        {
            groups.computeIfAbsent(MergeKey.of(cuboid, axis), ignored -> new ArrayList<>()).add(cuboid);
        }

        List<Cuboid> merged = new ArrayList<>(cuboids.size());

        for (List<Cuboid> group : groups.values())
        {
            group.sort(Comparator.comparingInt(cuboid -> axis.min(cuboid)));
            Cuboid current = group.get(0);

            for (int index = 1; index < group.size(); index++)
            {
                Cuboid next = group.get(index);

                if ((long) axis.max(current) + 1L == axis.min(next) &&
                        current.volume() + next.volume() <= MAX_FILL_VOLUME)
                {
                    current = axis.merge(current, next);
                }
                else
                {
                    merged.add(current);
                    current = next;
                }
            }

            merged.add(current);
        }

        return sortCuboids(merged);
    }

    private static List<Cuboid> sortCuboids(List<Cuboid> cuboids)
    {
        List<Cuboid> sorted = new ArrayList<>(cuboids);
        sorted.sort(Comparator
                .comparingInt((Cuboid cuboid) -> cuboid.min().x())
                .thenComparingInt(cuboid -> cuboid.min().y())
                .thenComparingInt(cuboid -> cuboid.min().z())
                .thenComparingInt(cuboid -> cuboid.max().x())
                .thenComparingInt(cuboid -> cuboid.max().y())
                .thenComparingInt(cuboid -> cuboid.max().z()));
        return sorted;
    }

    private static void appendChunkCuboids(LvcSiteWorkPlan.ChunkWork work, List<Cuboid> cuboids)
    {
        int chunkSize = LvcChunk.DEFAULT_SIZE;
        BitSet remaining = (BitSet) work.mask().clone();

        for (int index = remaining.nextSetBit(0); index >= 0; index = remaining.nextSetBit(index + 1))
        {
            int minX = index % chunkSize;
            int minY = (index / chunkSize) % chunkSize;
            int minZ = index / (chunkSize * chunkSize);
            int maxX = growX(remaining, minX, minY, minZ, chunkSize);
            int maxY = growY(remaining, minX, maxX, minY, minZ, chunkSize);
            int maxZ = growZ(remaining, minX, maxX, minY, maxY, minZ, chunkSize);

            clearCuboid(remaining, minX, maxX, minY, maxY, minZ, maxZ, chunkSize);
            LvcIntPosition chunkMin = new LvcIntPosition(
                    work.coordinate().x() * chunkSize,
                    work.coordinate().y() * chunkSize,
                    work.coordinate().z() * chunkSize);
            cuboids.add(new Cuboid(
                    chunkMin.offset(new LvcIntPosition(minX, minY, minZ)),
                    chunkMin.offset(new LvcIntPosition(maxX, maxY, maxZ))));
        }
    }

    private static int growX(BitSet mask, int minX, int y, int z, int size)
    {
        int maxX = minX;

        while (maxX + 1 < size && mask.get(LvcCapturePlanner.index(maxX + 1, y, z, size, size)))
        {
            maxX++;
        }

        return maxX;
    }

    private static int growY(BitSet mask, int minX, int maxX, int minY, int z, int size)
    {
        int maxY = minY;

        while (maxY + 1 < size && rowIsSet(mask, minX, maxX, maxY + 1, z, size))
        {
            maxY++;
        }

        return maxY;
    }

    private static int growZ(BitSet mask, int minX, int maxX, int minY, int maxY, int minZ, int size)
    {
        int maxZ = minZ;

        while (maxZ + 1 < size && planeIsSet(mask, minX, maxX, minY, maxY, maxZ + 1, size))
        {
            maxZ++;
        }

        return maxZ;
    }

    private static boolean rowIsSet(BitSet mask, int minX, int maxX, int y, int z, int size)
    {
        for (int x = minX; x <= maxX; x++)
        {
            if (!mask.get(LvcCapturePlanner.index(x, y, z, size, size)))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean planeIsSet(BitSet mask, int minX, int maxX, int minY, int maxY, int z, int size)
    {
        for (int y = minY; y <= maxY; y++)
        {
            if (!rowIsSet(mask, minX, maxX, y, z, size))
            {
                return false;
            }
        }

        return true;
    }

    private static void clearCuboid(BitSet mask, int minX, int maxX, int minY, int maxY,
                                    int minZ, int maxZ, int size)
    {
        for (int z = minZ; z <= maxZ; z++)
        {
            for (int y = minY; y <= maxY; y++)
            {
                int from = LvcCapturePlanner.index(minX, y, z, size, size);
                int to = LvcCapturePlanner.index(maxX, y, z, size, size) + 1;
                mask.clear(from, to);
            }
        }
    }

    public record Cuboid(LvcIntPosition min, LvcIntPosition max)
    {
        public Cuboid
        {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }

        public long volume()
        {
            return ((long) this.max.x() - this.min.x() + 1L) *
                    ((long) this.max.y() - this.min.y() + 1L) *
                    ((long) this.max.z() - this.min.z() + 1L);
        }
    }

    private enum Axis
    {
        X
        {
            @Override
            int min(Cuboid cuboid)
            {
                return cuboid.min().x();
            }

            @Override
            int max(Cuboid cuboid)
            {
                return cuboid.max().x();
            }

            @Override
            Cuboid merge(Cuboid first, Cuboid second)
            {
                return new Cuboid(first.min(), new LvcIntPosition(
                        second.max().x(), first.max().y(), first.max().z()));
            }
        },
        Y
        {
            @Override
            int min(Cuboid cuboid)
            {
                return cuboid.min().y();
            }

            @Override
            int max(Cuboid cuboid)
            {
                return cuboid.max().y();
            }

            @Override
            Cuboid merge(Cuboid first, Cuboid second)
            {
                return new Cuboid(first.min(), new LvcIntPosition(
                        first.max().x(), second.max().y(), first.max().z()));
            }
        },
        Z
        {
            @Override
            int min(Cuboid cuboid)
            {
                return cuboid.min().z();
            }

            @Override
            int max(Cuboid cuboid)
            {
                return cuboid.max().z();
            }

            @Override
            Cuboid merge(Cuboid first, Cuboid second)
            {
                return new Cuboid(first.min(), new LvcIntPosition(
                        first.max().x(), first.max().y(), second.max().z()));
            }
        };

        abstract int min(Cuboid cuboid);

        abstract int max(Cuboid cuboid);

        abstract Cuboid merge(Cuboid first, Cuboid second);
    }

    private record MergeKey(Axis axis, int firstMin, int firstMax, int secondMin, int secondMax)
    {
        private static MergeKey of(Cuboid cuboid, Axis axis)
        {
            return switch (axis)
            {
                case X -> new MergeKey(axis, cuboid.min().y(), cuboid.max().y(),
                        cuboid.min().z(), cuboid.max().z());
                case Y -> new MergeKey(axis, cuboid.min().x(), cuboid.max().x(),
                        cuboid.min().z(), cuboid.max().z());
                case Z -> new MergeKey(axis, cuboid.min().x(), cuboid.max().x(),
                        cuboid.min().y(), cuboid.max().y());
            };
        }
    }
}
