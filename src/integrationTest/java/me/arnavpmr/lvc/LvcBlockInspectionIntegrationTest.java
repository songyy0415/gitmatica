package me.arnavpmr.lvc;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import me.arnavpmr.lvc.overlay.LvcBlockInspectionPolicy;

final class LvcBlockInspectionIntegrationTest
{
    private LvcBlockInspectionIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run(
                "Gitmatica block inspection compares added and removed blocks",
                LvcBlockInspectionIntegrationTest::gitmaticaComparesAirMismatches);
        IntegrationTestSupport.run(
                "regular Litematica block inspection keeps one-sided air details",
                LvcBlockInspectionIntegrationTest::litematicaKeepsOneSidedAirDetails);
        IntegrationTestSupport.run(
                "Gitmatica compact block details use Before and After headings",
                LvcBlockInspectionIntegrationTest::gitmaticaUsesChangeHeadings);
    }

    private static void gitmaticaComparesAirMismatches()
    {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState block = Blocks.STONE.defaultBlockState();

        IntegrationTestSupport.assertTrue(
                LvcBlockInspectionPolicy.shouldRenderBlockComparison(true, block, air),
                "removed Gitmatica blocks should render a Before/After comparison");
        IntegrationTestSupport.assertTrue(
                LvcBlockInspectionPolicy.shouldRenderBlockComparison(true, air, block),
                "added Gitmatica blocks should render a Before/After comparison");
    }

    private static void litematicaKeepsOneSidedAirDetails()
    {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState block = Blocks.STONE.defaultBlockState();

        IntegrationTestSupport.assertTrue(
                !LvcBlockInspectionPolicy.shouldRenderBlockComparison(false, block, air),
                "regular Litematica missing blocks should keep the schematic-only panel");
        IntegrationTestSupport.assertTrue(
                !LvcBlockInspectionPolicy.shouldRenderBlockComparison(false, air, block),
                "regular Litematica extra blocks should keep the client-only panel");
        IntegrationTestSupport.assertTrue(
                LvcBlockInspectionPolicy.shouldRenderBlockComparison(
                        false, block, Blocks.DIRT.defaultBlockState()),
                "regular non-air mismatches should keep the two-column comparison");
        IntegrationTestSupport.assertTrue(
                !LvcBlockInspectionPolicy.shouldRenderBlockComparison(
                        true, Blocks.VOID_AIR.defaultBlockState(), block),
                "untracked Gitmatica positions should not render a comparison");
    }

    private static void gitmaticaUsesChangeHeadings()
    {
        IntegrationTestSupport.assertEquals(
                "Before:",
                LvcBlockInspectionPolicy.comparisonHeading(true, true, "Schematic:"),
                "Gitmatica schematic-side details should be titled Before");
        IntegrationTestSupport.assertEquals(
                "After:",
                LvcBlockInspectionPolicy.comparisonHeading(true, false, "Client:"),
                "Gitmatica client-side details should be titled After");
        IntegrationTestSupport.assertEquals(
                "Schematic:",
                LvcBlockInspectionPolicy.comparisonHeading(false, true, "Schematic:"),
                "regular Litematica schematic-side details should remain titled Schematic");
        IntegrationTestSupport.assertEquals(
                "Client:",
                LvcBlockInspectionPolicy.comparisonHeading(false, false, "Client:"),
                "regular Litematica client-side details should remain titled Client");
    }
}
