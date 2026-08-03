package me.arnavpmr.lvc.integration.litematica.verifier;

import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

/**
 * Attaches Gitmatica-only inventory details to Litematica's immutable mismatch
 * record without modifying or replacing that record.
 */
public final class VerifierMismatchMetadata
{
    private static final Map<BlockMismatch, InventoryDetails> INVENTORY_DETAILS = new IdentityHashMap<>();

    private VerifierMismatchMetadata()
    {
    }

    public static synchronized BlockMismatch inventoryMismatch(
            BlockState expected,
            BlockState found,
            BlockPos position,
            VerifierInventoryPreview preview)
    {
        BlockMismatch mismatch = new BlockMismatch(MismatchType.WRONG_STATE, expected, found, 1);
        INVENTORY_DETAILS.put(mismatch, new InventoryDetails(position.immutable(), preview));
        return mismatch;
    }

    public static synchronized BlockMismatch copyWithCount(BlockMismatch source, int count)
    {
        BlockMismatch copy = new BlockMismatch(
                source.mismatchType(),
                source.stateExpected(),
                source.stateFound(),
                count);
        InventoryDetails details = INVENTORY_DETAILS.get(source);

        if (details != null && count == 1)
        {
            INVENTORY_DETAILS.put(copy, details);
        }

        return copy;
    }

    public static synchronized boolean isInventoryMismatch(BlockMismatch mismatch)
    {
        return INVENTORY_DETAILS.containsKey(mismatch);
    }

    @Nullable
    public static synchronized BlockPos inventoryPosition(BlockMismatch mismatch)
    {
        InventoryDetails details = INVENTORY_DETAILS.get(mismatch);
        return details != null ? details.position() : null;
    }

    @Nullable
    public static synchronized VerifierInventoryPreview inventoryPreview(BlockMismatch mismatch)
    {
        InventoryDetails details = INVENTORY_DETAILS.get(mismatch);
        return details != null ? details.preview() : null;
    }

    public static synchronized void remove(BlockMismatch mismatch)
    {
        INVENTORY_DETAILS.remove(mismatch);
    }

    private record InventoryDetails(BlockPos position, VerifierInventoryPreview preview)
    {
    }
}
