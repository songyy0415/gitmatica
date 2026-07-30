package me.niicide.lvc.mixin.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.ArrayListMultimap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifier;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierState;
import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierStartGuard;
import me.niicide.lvc.gui.LvcVerifierStartWorkflow;
import me.niicide.lvc.integration.litematica.verifier.VerifierMismatchMetadata;
import me.niicide.lvc.integration.litematica.verifier.VerifierRenderFilter;

@Mixin(SchematicVerifier.class)
abstract class MixinSchematicVerifier implements GitmaticaVerifier
{
    @Shadow @Final private Minecraft mc;
    @Shadow private ClientLevel worldClient;
    @Shadow private WorldSchematic worldSchematic;
    @Shadow @Final private Object2ObjectOpenHashMap<BlockPos, BlockMismatch> blockMismatches;
    @Shadow @Final private HashSet<Pair<BlockState, BlockState>> ignoredMismatches;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missingBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> extraBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongStatesPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> diffBlocksPositions;
    @Shadow @Final private List<MismatchRenderPos> mismatchPositionsForRender;
    @Shadow @Final private List<BlockPos> mismatchBlockPositionsForRender;

    @Shadow protected abstract void checkBlockStates(
            int x,
            int y,
            int z,
            BlockState expected,
            BlockState found);

    @Shadow protected abstract void updateMismatchOverlays();

    @Unique
    private final GitmaticaVerifierState gitmatica$state = new GitmaticaVerifierState();

    @Inject(method = "clearData", at = @At("HEAD"))
    private void gitmatica$clearVerifierState(CallbackInfo callbackInfo)
    {
        this.gitmatica$markInventoryChunksForRebuild(this.gitmatica$state.inventoryMismatches().keySet());
        this.gitmatica$state.clear();
    }

    @Redirect(
            method = "verifyChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;checkBlockStates(IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void gitmatica$checkBlockAndInventory(
            SchematicVerifier verifier,
            int x,
            int y,
            int z,
            BlockState expected,
            BlockState found,
            ChunkAccess chunkClient,
            ChunkAccess chunkSchematic,
            IntBoundingBox box)
    {
        this.checkBlockStates(x, y, z, expected, found);
        BlockPos position = new BlockPos(x, y, z);
        this.gitmatica$state.removeInventoryMismatchIfContainerChanged(
                position, expected, found);

        if (!expected.hasBlockEntity() || !found.hasBlockEntity() || this.worldSchematic == null)
        {
            return;
        }

        Level foundWorld = WorldUtils.getBestWorld(this.mc);

        if (foundWorld == null)
        {
            foundWorld = this.worldClient;
        }

        if (foundWorld != null)
        {
            ChunkAccess foundChunk = chunkClient;

            if (foundWorld != this.worldClient &&
                foundWorld.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4))
            {
                foundChunk = foundWorld.getChunk(position.getX() >> 4, position.getZ() >> 4);
            }

            BlockState authoritativeFound = foundChunk.getBlockState(position);
            this.gitmatica$state.compareInventory(
                    position,
                    expected,
                    authoritativeFound,
                    chunkSchematic,
                    foundChunk,
                    this.worldSchematic,
                    foundWorld);
        }
    }

    @Inject(method = "startVerification", at = @At("HEAD"), cancellable = true)
    private void gitmatica$startAfterAuthoritativeScan(
            ClientLevel clientWorld,
            WorldSchematic schematicWorld,
            fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement,
            ICompletionListener completionListener,
            CallbackInfo callbackInfo)
    {
        if (GitmaticaVerifierStartGuard.isDirectStart())
        {
            return;
        }

        boolean handled = LvcVerifierStartWorkflow.startIfGitmatica(
                placement,
                completionListener,
                () ->
                {
                    if (GuiUtils.getCurrentScreen() instanceof GuiSchematicVerifier screen)
                    {
                        screen.initGui();
                    }
                });

        if (handled)
        {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "getSelectedMismatchPositionsForRender", at = @At("HEAD"), cancellable = true)
    private void gitmatica$filterStrongMismatchMarkers(
            CallbackInfoReturnable<List<MismatchRenderPos>> callbackInfo)
    {
        if (!this.gitmatica$strongFilterActive() || this.mc.player == null)
        {
            return;
        }

        int maximum = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();
        BlockPos center = BlockPos.containing(this.mc.player.position());
        callbackInfo.setReturnValue(this.gitmatica$state
                .sortedRenderFilterPositions(center, maximum));
    }

    @Inject(method = "getSelectedMismatchBlockPositionsForRender", at = @At("HEAD"), cancellable = true)
    private void gitmatica$filterStrongMismatchBlockMarkers(
            CallbackInfoReturnable<List<BlockPos>> callbackInfo)
    {
        if (!this.gitmatica$strongFilterActive() || this.mc.player == null)
        {
            return;
        }

        int maximum = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();
        BlockPos center = BlockPos.containing(this.mc.player.position());
        List<BlockPos> positions = new ArrayList<>();

        for (MismatchRenderPos position : this.gitmatica$state
                .sortedRenderFilterPositions(center, maximum))
        {
            positions.add(position.pos());
        }

        callbackInfo.setReturnValue(List.copyOf(positions));
    }

    @Unique
    private boolean gitmatica$strongFilterActive()
    {
        return this.gitmatica$state.renderFilter().active() &&
               (Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld());
    }

    @Inject(method = "getTotalErrors", at = @At("RETURN"), cancellable = true)
    private void gitmatica$includeInventoryErrors(CallbackInfoReturnable<Integer> callbackInfo)
    {
        callbackInfo.setReturnValue(callbackInfo.getReturnValue() + this.gitmatica$getWrongInventories());
    }

    @Inject(method = "updateMismatchOverlays", at = @At("TAIL"))
    private void gitmatica$applyExplicitSelectionAfterOverlayUpdate(CallbackInfo callbackInfo)
    {
        this.gitmatica$applyExplicitSelection();
    }

    @Override
    public Map<BlockPos, BlockMismatch> gitmatica$getBlockMismatchesByPosition()
    {
        return Map.copyOf(this.blockMismatches);
    }

    @Override
    public Map<BlockPos, BlockMismatch> gitmatica$getInventoryMismatchesByPosition()
    {
        return this.gitmatica$state.inventoryMismatches();
    }

    @Override
    public int gitmatica$getWrongInventories()
    {
        return this.gitmatica$state.inventoryMismatches().size();
    }

    @Override
    public boolean gitmatica$hasIgnoredMismatches()
    {
        return !this.ignoredMismatches.isEmpty() || this.gitmatica$state.hasHiddenMismatches();
    }

    @Override
    public void gitmatica$hideMismatch(BlockMismatch mismatch)
    {
        if (VerifierMismatchMetadata.isInventoryMismatch(mismatch))
        {
            BlockPos position = VerifierMismatchMetadata.inventoryPosition(mismatch);
            this.gitmatica$state.hideInventoryMismatch(mismatch);

            if (position != null)
            {
                this.gitmatica$markInventoryChunksForRebuild(Set.of(position));
            }
        }
        else
        {
            Map<BlockPos, BlockMismatch> hidden = new LinkedHashMap<>();

            for (Map.Entry<BlockPos, BlockMismatch> entry : this.blockMismatches.entrySet())
            {
                if (entry.getValue().equals(mismatch))
                {
                    hidden.put(entry.getKey().immutable(), entry.getValue());
                }
            }

            this.gitmatica$state.rememberHiddenBlockMismatches(hidden);
            ((SchematicVerifier) (Object) this).ignoreStateMismatch(mismatch);
        }
    }

    @Override
    public void gitmatica$resetHiddenMismatches()
    {
        ((SchematicVerifier) (Object) this).resetIgnoredStateMismatches();

        for (Map.Entry<BlockPos, BlockMismatch> entry : this.gitmatica$state.hiddenBlockMismatches().entrySet())
        {
            BlockMismatch mismatch = entry.getValue();
            this.blockMismatches.put(entry.getKey(), mismatch);
            this.gitmatica$positionsFor(mismatch.mismatchType()).put(
                    Pair.of(mismatch.stateExpected(), mismatch.stateFound()),
                    entry.getKey());
        }

        this.gitmatica$state.clearHiddenBlockMismatches();
        this.gitmatica$state.restoreHiddenInventoryMismatches();
        this.updateMismatchOverlays();
        this.gitmatica$markInventoryChunksForRebuild(this.gitmatica$state.inventoryMismatches().keySet());
    }

    @Override
    public void gitmatica$setChangeInfoHud(boolean enabled)
    {
        this.gitmatica$state.setChangeInfoHud(enabled);
    }

    @Override
    public boolean gitmatica$isChangeInfoHud()
    {
        return this.gitmatica$state.changeInfoHud();
    }

    @Override
    public void gitmatica$setMismatchPositionsSelected(Collection<MismatchRenderPos> positions)
    {
        this.gitmatica$state.setExplicitSelection(positions);
        this.gitmatica$applyExplicitSelection();
    }

    @Override
    public boolean gitmatica$setRenderThroughMismatchFilter(Collection<MismatchRenderPos> positions)
    {
        return this.gitmatica$state.setRenderFilter(positions);
    }

    @Override
    public boolean gitmatica$clearRenderThroughMismatchFilter()
    {
        return this.gitmatica$state.clearRenderFilter();
    }

    @Override
    public VerifierRenderFilter gitmatica$getRenderThroughMismatchFilter()
    {
        return this.gitmatica$state.renderFilter();
    }

    @Override
    public boolean gitmatica$hasInventoryMismatchForOverlay(BlockPos position)
    {
        return this.gitmatica$state.hasInventoryMismatch(position);
    }

    @Unique
    private void gitmatica$applyExplicitSelection()
    {
        if (!this.gitmatica$state.explicitSelectionEnabled() || this.mc.player == null)
        {
            return;
        }

        int maximum = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();
        BlockPos center = BlockPos.containing(this.mc.player.position());
        List<MismatchRenderPos> positions = this.gitmatica$state.sortedExplicitSelection(center, maximum);
        this.mismatchPositionsForRender.clear();
        this.mismatchBlockPositionsForRender.clear();

        for (MismatchRenderPos position : positions)
        {
            this.mismatchPositionsForRender.add(position);
            this.mismatchBlockPositionsForRender.add(position.pos());
        }
    }

    @Unique
    private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> gitmatica$positionsFor(MismatchType type)
    {
        return switch (type)
        {
            case MISSING -> this.missingBlocksPositions;
            case EXTRA -> this.extraBlocksPositions;
            case WRONG_BLOCK -> this.wrongBlocksPositions;
            case WRONG_STATE -> this.wrongStatesPositions;
            case DIFF_BLOCK -> this.diffBlocksPositions;
            default -> throw new IllegalArgumentException("Unsupported mismatch type " + type);
        };
    }

    @Unique
    private void gitmatica$markInventoryChunksForRebuild(Collection<BlockPos> positions)
    {
        for (BlockPos position : positions)
        {
            DataManager.getSchematicPlacementManager().markChunkForRebuild(
                    position.getX() >> 4,
                    position.getZ() >> 4);
        }
    }
}
