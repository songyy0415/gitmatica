package fi.dy.masa.litematica.schematic.verifier;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import me.niicide.lvc.diff.LvcSpatialDiffGroups;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Group;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.niicide.lvc.diff.LvcSpatialDiffGroups.PositionedChange;
import me.niicide.lvc.diff.LvcVerifierDiffGroups.Entry;
import me.niicide.lvc.gui.widgets.LvcChangeEntry;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierState;
import me.niicide.lvc.integration.litematica.verifier.VerifierInventoryPreview;
import me.niicide.lvc.integration.litematica.verifier.VerifierMismatchMetadata;
import me.niicide.lvc.integration.litematica.verifier.VerifierRenderFilter;

/**
 * Exercises the addon-owned verifier sidecar without relying on fork-only
 * constructors, enum constants, or package-private Litematica methods.
 */
public final class LvcVerifierHiddenMismatchIntegrationTest
{
    private LvcVerifierHiddenMismatchIntegrationTest()
    {
    }

    public static void runAll() throws Exception
    {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
        run("hidden mismatch state restores cleanly",
                LvcVerifierHiddenMismatchIntegrationTest::hiddenStateRestores);
        run("inventory metadata follows identity copies",
                LvcVerifierHiddenMismatchIntegrationTest::inventoryMetadataUsesIdentity);
        run("change viewer inventory previews require the inventory subgroup",
                LvcVerifierHiddenMismatchIntegrationTest::inventoryPreviewsRequireInventorySubgroup);
        run("removed containers discard stale inventory mismatch rows",
                LvcVerifierHiddenMismatchIntegrationTest::removedContainersDiscardInventoryMismatches);
        run("render filters are immutable and revisioned",
                LvcVerifierHiddenMismatchIntegrationTest::renderFiltersAreRevisioned);
    }

    private static void hiddenStateRestores()
    {
        GitmaticaVerifierState state = new GitmaticaVerifierState();
        BlockPos blockPosition = new BlockPos(1, 2, 3);
        BlockPos inventoryPosition = new BlockPos(4, 5, 6);
        BlockMismatch block = new BlockMismatch(
                MismatchType.WRONG_BLOCK, null, null, 1);
        BlockMismatch inventory = VerifierMismatchMetadata.inventoryMismatch(
                null, null, inventoryPosition, null);

        state.rememberHiddenBlockMismatches(Map.of(blockPosition, block));
        state.hideInventoryMismatch(inventory);
        assertTrue(state.hasHiddenMismatches(),
                "block and inventory hides should enable Reset Hidden");

        state.clearHiddenBlockMismatches();
        state.restoreHiddenInventoryMismatches();
        assertTrue(!state.hasHiddenMismatches(),
                "Reset Hidden should clear every hidden category");
        VerifierMismatchMetadata.remove(inventory);
    }

    private static void inventoryMetadataUsesIdentity()
    {
        BlockPos position = new BlockPos(7, 8, 9);
        BlockMismatch inventory = VerifierMismatchMetadata.inventoryMismatch(
                null, null, position, null);
        BlockMismatch copy = VerifierMismatchMetadata.copyWithCount(inventory, 1);
        BlockMismatch aggregate = VerifierMismatchMetadata.copyWithCount(inventory, 2);

        assertTrue(VerifierMismatchMetadata.isInventoryMismatch(inventory),
                "source row should retain inventory identity");
        assertEquals(position, VerifierMismatchMetadata.inventoryPosition(copy),
                "singleton copies should retain their inventory position");
        assertTrue(!VerifierMismatchMetadata.isInventoryMismatch(aggregate),
                "aggregated rows must not claim one inventory position");

        VerifierMismatchMetadata.remove(inventory);
        VerifierMismatchMetadata.remove(copy);
    }

    private static void inventoryPreviewsRequireInventorySubgroup()
    {
        BlockPos position = new BlockPos(3, 4, 5);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        VerifierInventoryPreview preview = new VerifierInventoryPreview(
                position, null, null);
        BlockMismatch mismatch = VerifierMismatchMetadata.inventoryMismatch(
                chest, chest, position, preview);
        Entry data = new Entry(mismatch, List.of(position));
        Group<Entry> group = LvcSpatialDiffGroups.build(List.of(
                new PositionedChange<>(
                        position, Kind.INVENTORIES_CHANGED, data))).getFirst();

        assertEquals(
                preview,
                LvcChangeEntry.data(
                        group, 1, Kind.INVENTORIES_CHANGED, data).inventoryPreview(),
                "inventory subgroup rows should expose their inventory preview");
        assertTrue(
                LvcChangeEntry.data(
                        group, 1, Kind.BLOCKS_REMOVED, data).inventoryPreview() == null,
                "non-inventory subgroup rows must not expose attached container previews");
        VerifierMismatchMetadata.remove(mismatch);
    }

    private static void removedContainersDiscardInventoryMismatches()
            throws Exception
    {
        GitmaticaVerifierState state = new GitmaticaVerifierState();
        BlockPos position = new BlockPos(6, 7, 8);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockState rotatedChest = chest.setValue(ChestBlock.FACING, Direction.EAST);
        BlockMismatch visible = VerifierMismatchMetadata.inventoryMismatch(
                chest, chest, position, null);
        inventoryMismatches(state).put(position, visible);

        state.removeInventoryMismatchIfContainerChanged(
                position, chest, rotatedChest);
        assertTrue(
                state.hasInventoryMismatch(position),
                "a blockstate change on the same container should retain its inventory mismatch");

        state.removeInventoryMismatchIfContainerChanged(
                position, chest, Blocks.AIR.defaultBlockState());
        assertTrue(
                !state.hasInventoryMismatch(position),
                "removing the container should discard its visible inventory mismatch");

        BlockMismatch hidden = VerifierMismatchMetadata.inventoryMismatch(
                chest, chest, position, null);
        inventoryMismatches(state).put(position, hidden);
        state.hideInventoryMismatch(hidden);
        state.removeInventoryMismatchIfContainerChanged(
                position, chest, Blocks.AIR.defaultBlockState());
        state.restoreHiddenInventoryMismatches();
        assertTrue(
                !state.hasInventoryMismatch(position),
                "Reset Hidden must not restore an inventory mismatch for a removed container");
        assertTrue(
                !state.hasHiddenMismatches(),
                "removing a hidden container mismatch should clear its ignored position");
    }

    private static void renderFiltersAreRevisioned()
    {
        GitmaticaVerifierState state = new GitmaticaVerifierState();
        BlockPos added = new BlockPos(1, 2, 3);
        BlockPos changed = new BlockPos(4, 5, 6);
        List<MismatchRenderPos> visible = List.of(
                new MismatchRenderPos(MismatchType.EXTRA, added),
                new MismatchRenderPos(MismatchType.WRONG_STATE, changed));

        assertTrue(state.setRenderFilter(visible),
                "first category filter should change verifier state");
        VerifierRenderFilter filter = state.renderFilter();
        long revision = filter.revision();
        assertTrue(filter.active(), "category filter should be active");
        assertTrue(filter.includes(MismatchType.EXTRA, added),
                "included positions should pass");
        assertTrue(!filter.includes(MismatchType.MISSING, added),
                "another mismatch type at the same position should not pass");
        assertTrue(!state.setRenderFilter(visible),
                "an equivalent filter should not advance state");
        assertEquals(revision, state.renderFilter().revision(),
                "equivalent filters should retain their revision");

        assertTrue(state.setRenderFilter(List.of(
                        new MismatchRenderPos(MismatchType.MISSING, added))),
                "a different filter should advance state");
        assertTrue(state.renderFilter().revision() > revision,
                "different filters should advance the revision");
        assertTrue(state.clearRenderFilter(), "All should clear the filter");
        assertTrue(!state.renderFilter().active(),
                "All should restore unfiltered rendering");
        assertTrue(state.renderFilter().includes(MismatchType.EXTRA, added),
                "inactive filters should include every mismatch");
    }

    private static void run(String name, ThrowingRunnable test) throws Exception
    {
        try
        {
            test.run();
            System.out.println("[PASS] " + name);
        }
        catch (Throwable throwable)
        {
            System.err.println("[FAIL] " + name);

            if (throwable instanceof Exception exception)
            {
                throw exception;
            }

            throw new RuntimeException(throwable);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError(
                    message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, BlockMismatch> inventoryMismatches(
            GitmaticaVerifierState state) throws Exception
    {
        Field field = GitmaticaVerifierState.class.getDeclaredField(
                "inventoryMismatches");
        field.setAccessible(true);
        return (Map<BlockPos, BlockMismatch>) field.get(state);
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
