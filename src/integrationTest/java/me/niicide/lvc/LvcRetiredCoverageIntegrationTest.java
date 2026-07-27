package me.niicide.lvc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.niicide.lvc.capture.LvcRetiredCoveragePlan;
import me.niicide.lvc.capture.LvcSiteWorkPlan;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.semantic.LvcTrackedBlockCursor;

final class LvcRetiredCoverageIntegrationTest
{
    private LvcRetiredCoverageIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("retired coverage subtracts updated subregion union",
                LvcRetiredCoverageIntegrationTest::retiredCoverageSubtractsUpdatedSubregionUnion);
        IntegrationTestSupport.run("retired coverage handles moved bounds across negative chunks",
                LvcRetiredCoverageIntegrationTest::retiredCoverageHandlesMovedBoundsAcrossNegativeChunks);
        IntegrationTestSupport.run("retired coverage ignores renamed and expanded bounds",
                LvcRetiredCoverageIntegrationTest::retiredCoverageIgnoresRenamedAndExpandedBounds);
        IntegrationTestSupport.run("retired coverage compresses rectangular masks into fill cuboids",
                LvcRetiredCoverageIntegrationTest::retiredCoverageCompressesRectangularMasksIntoFillCuboids);
    }

    private static void retiredCoverageSubtractsUpdatedSubregionUnion()
    {
        LvcManifest.Site previous = site(
                region("A", 0, 0, 0, 4, 2, 1),
                region("B", 3, 0, 0, 3, 1, 1));
        LvcManifest.Site updated = site(
                region("A", 0, 0, 0, 2, 2, 1),
                region("B", 3, 0, 0, 3, 1, 1));
        LvcRetiredCoveragePlan plan = LvcRetiredCoveragePlan.between(previous, updated);

        IntegrationTestSupport.assertEquals(
                Set.of(
                        new LvcIntPosition(2, 0, 0),
                        new LvcIntPosition(2, 1, 0),
                        new LvcIntPosition(3, 1, 0)),
                projectPositions(plan),
                "retired coverage should preserve positions covered by any updated subregion");
        IntegrationTestSupport.assertEquals(3, plan.blockCount(), "retired union-difference block count");
    }

    private static void retiredCoverageHandlesMovedBoundsAcrossNegativeChunks()
    {
        LvcRetiredCoveragePlan plan = LvcRetiredCoveragePlan.between(
                site(region("Moved", -1, 0, 0, 2, 1, 1)),
                site(region("Moved", 0, 0, 0, 2, 1, 1)));

        IntegrationTestSupport.assertEquals(
                Set.of(new LvcIntPosition(-1, 0, 0)),
                projectPositions(plan),
                "moving a subregion should retire only its uncovered old position");
        IntegrationTestSupport.assertEquals(
                new LvcChunkCoordinate(-1, 0, 0),
                plan.chunks().get(0).coordinate(),
                "negative retired position should use floor-divided LVC chunk");
    }

    private static void retiredCoverageIgnoresRenamedAndExpandedBounds()
    {
        LvcManifest.Site previous = site(region("Before", 0, 0, 0, 2, 2, 2));
        LvcManifest.Site renamedAndExpanded = site(region("After", 0, 0, 0, 3, 2, 2));
        LvcRetiredCoveragePlan plan = LvcRetiredCoveragePlan.between(previous, renamedAndExpanded);

        IntegrationTestSupport.assertTrue(plan.isEmpty(),
                "renaming or expanding a subregion should not retire still-covered positions");
    }

    private static void retiredCoverageCompressesRectangularMasksIntoFillCuboids()
    {
        LvcRetiredCoveragePlan plan = LvcRetiredCoveragePlan.between(
                site(region("Wide", 0, 0, 0, 144, 16, 16)),
                site(region("Wide", 144, 0, 0, 1, 1, 1)));

        IntegrationTestSupport.assertEquals(
                List.of(
                        new LvcRetiredCoveragePlan.Cuboid(
                                new LvcIntPosition(0, 0, 0),
                                new LvcIntPosition(127, 15, 15)),
                        new LvcRetiredCoveragePlan.Cuboid(
                                new LvcIntPosition(128, 0, 0),
                                new LvcIntPosition(143, 15, 15))),
                plan.cuboids(),
                "adjacent LVC chunks should coalesce without exceeding the fill volume limit");
        IntegrationTestSupport.assertEquals(36_864, plan.blockCount(), "retired slab block count");
    }

    private static Set<LvcIntPosition> projectPositions(LvcRetiredCoveragePlan plan)
    {
        Set<LvcIntPosition> positions = new HashSet<>();

        for (LvcSiteWorkPlan.ChunkWork work : plan.chunks())
        {
            for (LvcTrackedBlockCursor.Position position : LvcTrackedBlockCursor.positions(
                    work.coordinate(), new LvcIntPosition(0, 0, 0), work.mask(),
                    LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE, LvcChunk.DEFAULT_SIZE))
            {
                positions.add(position.projectPos());
            }
        }

        return positions;
    }

    private static LvcManifest.Site site(LvcManifest.Region... regions)
    {
        return new LvcManifest.Site(
                "main", "Main", "minecraft:overworld", List.of(regions), Map.of());
    }

    private static LvcManifest.Region region(String name, int x, int y, int z,
                                             int sizeX, int sizeY, int sizeZ)
    {
        return new LvcManifest.Region(
                name, List.of(x, y, z), List.of(sizeX, sizeY, sizeZ));
    }
}
