package me.niicide.lvc.diff;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Group;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.PositionedChange;

public final class LvcSpatialDiffGroupsIntegrationTest
{
    private LvcSpatialDiffGroupsIntegrationTest()
    {
    }

    public static void runAll()
    {
        run("diff groups use six-direction spatial connectivity", LvcSpatialDiffGroupsIntegrationTest::sixDirectionConnectivity);
        run("diff groups keep diagonal changes separate and deterministically ordered", LvcSpatialDiffGroupsIntegrationTest::diagonalSeparationAndOrder);
        run("diff group entry projection follows subgroup filters without losing entries", LvcSpatialDiffGroupsIntegrationTest::filteredEntryProjection);
    }

    private static void sixDirectionConnectivity()
    {
        BlockPos center = new BlockPos(0, 0, 0);
        List<BlockPos> positions = List.of(center, center.east(), center.west(), center.above(), center.below(),
                center.north(), center.south());
        List<PositionedChange<String>> changes = new ArrayList<>();

        for (BlockPos position : positions)
        {
            changes.add(new PositionedChange<>(position, Kind.BLOCKS_ADDED, position.toShortString()));
        }

        changes.add(new PositionedChange<>(center, Kind.INVENTORIES_CHANGED, "inventory"));

        List<Group<String>> groups = LvcSpatialDiffGroups.build(changes);
        assertEquals(1, groups.size(), "face-adjacent changes should form one group");
        assertEquals(positions.size(), groups.get(0).entries(Kind.BLOCKS_ADDED).size(),
                "group should contain every face-adjacent payload");
        assertEquals(List.of("inventory"), groups.get(0).entries(Kind.INVENTORIES_CHANGED),
                "different change payloads at one position should share the spatial group");
        assertEquals(List.of(), groups.get(0).entries(Kind.BLOCKS_REMOVED),
                "categories without changes should remain available and empty");
    }

    private static void diagonalSeparationAndOrder()
    {
        List<PositionedChange<String>> changes = List.of(
                change(new BlockPos(10, 0, 0)),
                change(new BlockPos(10, 0, 1)),
                change(new BlockPos(2, 2, 2)),
                change(new BlockPos(3, 3, 3))
        );

        List<Group<String>> groups = LvcSpatialDiffGroups.build(changes);
        assertEquals(3, groups.size(), "diagonal-only changes should remain separate");
        assertEquals(new BlockPos(2, 2, 2), groups.get(0).anchor(), "groups should sort by their lowest coordinate");
        assertEquals(new BlockPos(3, 3, 3), groups.get(1).anchor(), "second group anchor");
        assertEquals(new BlockPos(10, 0, 0), groups.get(2).anchor(), "face-adjacent group anchor");
        assertEquals(2, groups.get(2).entries(Kind.BLOCKS_CHANGED).size(),
                "face-adjacent payloads should stay together");
    }

    private static PositionedChange<String> change(BlockPos position)
    {
        return new PositionedChange<>(position, Kind.BLOCKS_CHANGED, position.toShortString());
    }

    private static void filteredEntryProjection()
    {
        BlockPos pos = BlockPos.ZERO;
        Group<String> group = LvcSpatialDiffGroups.build(List.of(
                new PositionedChange<>(pos, Kind.INVENTORIES_CHANGED, "inventory"),
                new PositionedChange<>(pos, Kind.BLOCKS_ADDED, "added"),
                new PositionedChange<>(pos, Kind.BLOCKS_REMOVED, "removed"),
                new PositionedChange<>(pos, Kind.BLOCKS_CHANGED, "changed block"),
                new PositionedChange<>(pos, Kind.BLOCKSTATE_CHANGED, "changed state")
        )).getFirst();

        assertEquals(List.of("added"), group.allEntries(kind -> kind == Kind.BLOCKS_ADDED),
                "added filter should project only added entries");
        assertEquals(List.of("changed block", "changed state"),
                group.allEntries(kind -> kind == Kind.BLOCKS_CHANGED || kind == Kind.BLOCKSTATE_CHANGED),
                "changed filter should project both changed subgroups");
        assertEquals(List.of("inventory", "added", "removed", "changed block", "changed state"), group.allEntries(),
                "unfiltered projection should still contain every subgroup");
    }

    private static void run(String name, Runnable test)
    {
        try
        {
            test.run();
            System.out.println("[PASS] " + name);
        }
        catch (Throwable throwable)
        {
            System.err.println("[FAIL] " + name);
            throw throwable;
        }
    }

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (expected.equals(actual) == false)
        {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
