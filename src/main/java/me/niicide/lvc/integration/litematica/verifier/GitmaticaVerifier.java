package me.niicide.lvc.integration.litematica.verifier;

import java.util.Collection;
import java.util.Map;

import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;

/**
 * Stable addon-facing contract implemented on Litematica's verifier by a mixin.
 * Gitmatica code depends on this interface instead of fork-only methods.
 */
public interface GitmaticaVerifier
{
    Map<BlockPos, BlockMismatch> gitmatica$getBlockMismatchesByPosition();

    Map<BlockPos, BlockMismatch> gitmatica$getInventoryMismatchesByPosition();

    int gitmatica$getWrongInventories();

    boolean gitmatica$hasIgnoredMismatches();

    void gitmatica$hideMismatch(BlockMismatch mismatch);

    void gitmatica$resetHiddenMismatches();

    void gitmatica$setChangeInfoHud(boolean enabled);

    boolean gitmatica$isChangeInfoHud();

    void gitmatica$setMismatchPositionsSelected(Collection<MismatchRenderPos> positions);

    boolean gitmatica$setRenderThroughMismatchFilter(Collection<MismatchRenderPos> positions);

    boolean gitmatica$clearRenderThroughMismatchFilter();

    VerifierRenderFilter gitmatica$getRenderThroughMismatchFilter();

    boolean gitmatica$hasInventoryMismatchForOverlay(BlockPos position);
}
