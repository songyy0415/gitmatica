package me.niicide.lvc.overlay;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class LvcBlockInspectionPolicy
{
    private LvcBlockInspectionPolicy()
    {
    }

    public static BlockState comparisonAirSentinel(boolean semanticTrackingPlacement)
    {
        return semanticTrackingPlacement
                ? Blocks.VOID_AIR.defaultBlockState()
                : Blocks.AIR.defaultBlockState();
    }

    public static boolean isAirForCompactComparison(
            boolean semanticTrackingPlacement,
            BlockState state)
    {
        return !semanticTrackingPlacement && state.isAir();
    }

    public static boolean shouldRenderBlockComparison(
            boolean semanticTrackingPlacement,
            BlockState stateSchematic,
            BlockState stateClient)
    {
        BlockState air = comparisonAirSentinel(semanticTrackingPlacement);
        BlockState voidAir = Blocks.VOID_AIR.defaultBlockState();
        return stateSchematic != stateClient &&
               stateClient != air &&
               stateSchematic != air &&
               stateSchematic != voidAir;
    }

    public static String comparisonHeading(
            boolean semanticTrackingPlacement,
            boolean schematicSide,
            String original)
    {
        if (!semanticTrackingPlacement)
        {
            return original;
        }

        String source = schematicSide ? "Schematic:" : "Client:";
        String heading = schematicSide ? "Before:" : "After:";
        return original.endsWith(source)
                ? original.substring(0, original.length() - source.length()) + heading
                : heading;
    }
}
