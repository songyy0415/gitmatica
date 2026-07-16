package fi.dy.masa.litematica.schematic.verifier;

import java.lang.reflect.Field;
import java.util.List;
import com.google.common.collect.ArrayListMultimap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchOverlayFilter;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

public final class LvcVerifierHiddenMismatchIntegrationTest
{
    private LvcVerifierHiddenMismatchIntegrationTest()
    {
    }

    public static void runAll() throws Exception
    {
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
        run("singleton grouped verifier rows hide by position", LvcVerifierHiddenMismatchIntegrationTest::singletonRowsHideByPosition);
        run("reset hidden restores block and inventory rows", LvcVerifierHiddenMismatchIntegrationTest::resetRestoresHiddenRows);
        run("render-through mismatch filters are immutable and revisioned", LvcVerifierHiddenMismatchIntegrationTest::renderThroughFiltersAreRevisioned);
    }

    private static void singletonRowsHideByPosition() throws Exception
    {
        SchematicVerifier verifier = new SchematicVerifier("integration-test");
        BlockPos firstPos = new BlockPos(1, 2, 3);
        BlockPos secondPos = new BlockPos(8, 9, 10);
        BlockMismatch first = addBlockMismatch(verifier, firstPos, Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        BlockMismatch second = addBlockMismatch(verifier, secondPos, Blocks.OAK_PLANKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());

        verifier.ignoreStateMismatch(singletonGroupRow(first, firstPos), false);
        verifier.ignoreStateMismatch(singletonGroupRow(second, secondPos), false);

        assertTrue(verifier.getBlockMismatchesByPosition().isEmpty(),
                "each singleton row should be removed even when its grouped row carries a preview position");
        assertTrue(verifier.hasIgnoredStateMismatches(), "hidden block rows should enable Reset Hidden");
    }

    private static void resetRestoresHiddenRows() throws Exception
    {
        SchematicVerifier verifier = new SchematicVerifier("integration-test");
        BlockPos blockPos = new BlockPos(4, 5, 6);
        BlockPos inventoryPos = new BlockPos(7, 8, 9);
        BlockMismatch block = addBlockMismatch(verifier, blockPos, Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        BlockMismatch inventory = addInventoryMismatch(verifier, inventoryPos);

        verifier.ignoreStateMismatch(inventory, false);
        assertTrue(verifier.hasIgnoredStateMismatches(), "an inventory-only hide should enable Reset Hidden");
        verifier.ignoreStateMismatch(singletonGroupRow(block, blockPos), false);

        assertTrue(verifier.getBlockMismatchesByPosition().isEmpty(), "block row should be hidden before reset");
        assertTrue(verifier.getInventoryMismatchesByPosition().isEmpty(), "inventory row should be hidden before reset");
        verifier.restoreIgnoredStateMismatches();

        assertEquals(block, verifier.getBlockMismatchesByPosition().get(blockPos), "Reset Hidden should restore the block row");
        assertEquals(inventory, verifier.getInventoryMismatchesByPosition().get(inventoryPos), "Reset Hidden should restore the inventory row");
        assertEquals(2, verifier.getTotalErrors(), "Reset Hidden should restore verifier overview counts");
        assertTrue(verifier.hasIgnoredStateMismatches() == false, "Reset Hidden should clear every hidden category");
    }

    private static void renderThroughFiltersAreRevisioned()
    {
        SchematicVerifier verifier = new SchematicVerifier("integration-test");
        BlockPos added = new BlockPos(1, 2, 3);
        BlockPos inventory = new BlockPos(4, 5, 6);
        List<MismatchRenderPos> visible = List.of(
                new MismatchRenderPos(MismatchType.EXTRA, added),
                new MismatchRenderPos(MismatchType.WRONG_INVENTORIES, inventory)
        );

        assertTrue(verifier.setRenderThroughMismatchFilter(visible), "first filter should change verifier state");
        MismatchOverlayFilter filter = verifier.getRenderThroughMismatchFilter();
        long revision = filter.revision();
        assertTrue(filter.active(), "category filter should activate render-through filtering");
        assertTrue(filter.includes(MismatchType.EXTRA, added), "visible added position should pass the filter");
        assertTrue(filter.includes(MismatchType.WRONG_INVENTORIES, inventory), "visible inventory position should pass the filter");
        assertTrue(filter.includes(MismatchType.MISSING, added) == false, "filtered-out mismatch type should not pass at the same position");
        assertTrue(verifier.setRenderThroughMismatchFilter(visible) == false, "equivalent filter should not queue another rebuild");
        assertEquals(revision, verifier.getRenderThroughMismatchFilter().revision(), "equivalent filter should keep its revision");

        assertTrue(verifier.setRenderThroughMismatchFilter(List.of(new MismatchRenderPos(MismatchType.MISSING, added))),
                "different filter should advance verifier state");
        assertTrue(verifier.getRenderThroughMismatchFilter().revision() > revision, "different filter should advance revision");
        assertTrue(verifier.getRenderThroughMismatchFilter().includes(MismatchType.EXTRA, added) == false,
                "old visible type should be removed after a filter change");
        assertTrue(verifier.clearRenderThroughMismatchFilter(), "All should clear the render-through filter");
        assertTrue(verifier.getRenderThroughMismatchFilter().active() == false, "All should restore unfiltered rendering");
        assertTrue(verifier.getRenderThroughMismatchFilter().includes(MismatchType.EXTRA, added),
                "inactive filter should allow every mismatch again");
    }

    private static BlockMismatch addBlockMismatch(SchematicVerifier verifier, BlockPos pos,
                                                   BlockState expected, BlockState found) throws Exception
    {
        BlockMismatch mismatch = new BlockMismatch(MismatchType.WRONG_BLOCK, expected, found, 1);
        blockMismatches(verifier).put(pos, mismatch);
        mismatchPositions(verifier, "wrongBlocksPositions").put(Pair.of(expected, found), pos);
        return mismatch;
    }

    private static BlockMismatch addInventoryMismatch(SchematicVerifier verifier, BlockPos pos) throws Exception
    {
        BlockState state = Blocks.CHEST.defaultBlockState();
        BlockMismatch mismatch = new BlockMismatch(MismatchType.WRONG_INVENTORIES, state, state, 1, pos, null);
        inventoryMismatches(verifier).put(pos, mismatch);
        mismatchPositions(verifier, "wrongInventoriesPositions").put(Pair.of(state, state), pos);
        return mismatch;
    }

    private static BlockMismatch singletonGroupRow(BlockMismatch mismatch, BlockPos pos)
    {
        return new BlockMismatch(mismatch.mismatchType, mismatch.stateExpected, mismatch.stateFound, 1, pos, null);
    }

    @SuppressWarnings("unchecked")
    private static Object2ObjectOpenHashMap<BlockPos, BlockMismatch> blockMismatches(SchematicVerifier verifier) throws Exception
    {
        return (Object2ObjectOpenHashMap<BlockPos, BlockMismatch>) field("blockMismatches").get(verifier);
    }

    @SuppressWarnings("unchecked")
    private static Object2ObjectOpenHashMap<BlockPos, BlockMismatch> inventoryMismatches(SchematicVerifier verifier) throws Exception
    {
        return (Object2ObjectOpenHashMap<BlockPos, BlockMismatch>) field("inventoryMismatches").get(verifier);
    }

    @SuppressWarnings("unchecked")
    private static ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> mismatchPositions(
            SchematicVerifier verifier, String name) throws Exception
    {
        return (ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos>) field(name).get(verifier);
    }

    private static Field field(String name) throws Exception
    {
        Field field = SchematicVerifier.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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
        if (expected.equals(actual) == false)
        {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message)
    {
        if (condition == false)
        {
            throw new AssertionError(message);
        }
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
