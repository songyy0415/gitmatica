package fi.dy.masa.litematica.schematic.verifier;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierState;
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
        run("hidden mismatch state restores cleanly",
                LvcVerifierHiddenMismatchIntegrationTest::hiddenStateRestores);
        run("inventory metadata follows identity copies",
                LvcVerifierHiddenMismatchIntegrationTest::inventoryMetadataUsesIdentity);
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

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
