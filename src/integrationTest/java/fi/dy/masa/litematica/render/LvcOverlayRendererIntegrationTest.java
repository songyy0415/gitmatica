package fi.dy.masa.litematica.render;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class LvcOverlayRendererIntegrationTest
{
    private LvcOverlayRendererIntegrationTest()
    {
    }

    public static void runAll()
    {
        run("Gitmatica held-I overlay compares added and removed blocks",
                LvcOverlayRendererIntegrationTest::gitmaticaComparesAirMismatches);
        run("regular Litematica held-I overlay keeps one-sided missing block details",
                LvcOverlayRendererIntegrationTest::litematicaKeepsOneSidedAirDetails);
        run("compact block details use Gitmatica Before and After headings",
                LvcOverlayRendererIntegrationTest::compactBlockDetailsUseGitmaticaHeadings);
    }

    private static void gitmaticaComparesAirMismatches()
    {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState block = Blocks.STONE.defaultBlockState();

        assertTrue(OverlayRenderer.shouldRenderBlockComparison(true, block, air),
                "removed Gitmatica blocks should render a Before/After comparison");
        assertTrue(OverlayRenderer.shouldRenderBlockComparison(true, air, block),
                "added Gitmatica blocks should render a Before/After comparison");
    }

    private static void litematicaKeepsOneSidedAirDetails()
    {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState block = Blocks.STONE.defaultBlockState();

        assertTrue(OverlayRenderer.shouldRenderBlockComparison(false, block, air) == false,
                "regular Litematica missing blocks should keep the schematic-only panel");
        assertTrue(OverlayRenderer.shouldRenderBlockComparison(false, air, block) == false,
                "regular Litematica extra blocks should keep the client-only panel");
        assertTrue(OverlayRenderer.shouldRenderBlockComparison(false, block, Blocks.DIRT.defaultBlockState()),
                "regular non-air mismatches should keep the two-column comparison");
        assertTrue(OverlayRenderer.shouldRenderBlockComparison(true, Blocks.VOID_AIR.defaultBlockState(), block) == false,
                "untracked Gitmatica positions should not render a comparison");
    }

    private static void compactBlockDetailsUseGitmaticaHeadings()
    {
        assertEquals("Before", OverlayRenderer.blockInfoLineHeading(true, true, true),
                "Gitmatica schematic-side details should be titled Before");
        assertEquals("After", OverlayRenderer.blockInfoLineHeading(true, true, false),
                "Gitmatica client-side details should be titled After");
        assertEquals("Schematic", OverlayRenderer.blockInfoLineHeading(true, false, true),
                "unchanged Gitmatica details should use the normal Litematica heading");
        assertEquals("Schematic", OverlayRenderer.blockInfoLineHeading(false, true, true),
                "regular Litematica schematic-side details should remain titled Schematic");
        assertEquals("Client", OverlayRenderer.blockInfoLineHeading(false, true, false),
                "regular Litematica client-side details should remain titled Client");
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

    private static void assertTrue(boolean condition, String message)
    {
        if (condition == false)
        {
            throw new AssertionError(message);
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
