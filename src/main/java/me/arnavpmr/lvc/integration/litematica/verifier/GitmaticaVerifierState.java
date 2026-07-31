package me.arnavpmr.lvc.integration.litematica.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.world.WorldSchematic;

public final class GitmaticaVerifierState
{
    private final Map<BlockPos, BlockMismatch> inventoryMismatches = new LinkedHashMap<>();
    private final Map<BlockPos, BlockMismatch> hiddenInventoryMismatches = new LinkedHashMap<>();
    private final Map<BlockPos, BlockMismatch> hiddenBlockMismatches = new LinkedHashMap<>();
    private final Set<BlockPos> ignoredInventoryPositions = new java.util.HashSet<>();
    private List<MismatchRenderPos> explicitSelection = List.of();
    private boolean explicitSelectionEnabled;
    private boolean changeInfoHud;
    private VerifierRenderFilter renderFilter = VerifierRenderFilter.inactive(0L);

    public void clear()
    {
        this.inventoryMismatches.values().forEach(VerifierMismatchMetadata::remove);
        this.hiddenInventoryMismatches.values().forEach(VerifierMismatchMetadata::remove);
        this.inventoryMismatches.clear();
        this.hiddenInventoryMismatches.clear();
        this.hiddenBlockMismatches.clear();
        this.ignoredInventoryPositions.clear();
        this.explicitSelection = List.of();
        this.explicitSelectionEnabled = false;
        this.renderFilter = VerifierRenderFilter.inactive(this.renderFilter.revision() + 1L);
    }

    public void compareInventory(
            BlockPos position,
            BlockState expectedState,
            BlockState foundState,
            ChunkAccess expectedChunk,
            ChunkAccess foundChunk,
            WorldSchematic expectedWorld,
            Level foundWorld)
    {
        this.removeInventoryMismatch(position);

        BlockEntity expectedBlockEntity = expectedChunk.getBlockEntity(position);
        BlockEntity foundBlockEntity = foundChunk.getBlockEntity(position);

        if (!(expectedBlockEntity instanceof Container expectedContainer) ||
            !(foundBlockEntity instanceof Container foundContainer) ||
            expectedBlockEntity.getType() != foundBlockEntity.getType() ||
            expectedContainer.getContainerSize() != foundContainer.getContainerSize())
        {
            return;
        }

        VerifierInventorySide expected = VerifierInventorySide.ofBlockEntity(
                expectedBlockEntity, expectedWorld.registryAccess());
        VerifierInventorySide found = VerifierInventorySide.ofBlockEntity(
                foundBlockEntity, foundWorld.registryAccess());

        if (expected == null || found == null ||
            expected.inventory().getContainerSize() != found.inventory().getContainerSize())
        {
            return;
        }

        VerifierInventoryPreview preview = new VerifierInventoryPreview(position, expected, found);

        if (!preview.hasDiffs())
        {
            return;
        }

        BlockMismatch mismatch = VerifierMismatchMetadata.inventoryMismatch(
                expectedState, foundState, position, preview);

        if (this.ignoredInventoryPositions.contains(position))
        {
            this.hiddenInventoryMismatches.put(position.immutable(), mismatch);
        }
        else
        {
            this.inventoryMismatches.put(position.immutable(), mismatch);
        }
    }

    public void removeInventoryMismatchIfContainerChanged(
            BlockPos position,
            BlockState expectedState,
            BlockState foundState)
    {
        if (expectedState.getBlock() == foundState.getBlock() &&
            expectedState.hasBlockEntity() && foundState.hasBlockEntity())
        {
            return;
        }

        this.removeInventoryMismatch(position);
        this.ignoredInventoryPositions.remove(position);
    }

    public Map<BlockPos, BlockMismatch> inventoryMismatches()
    {
        return Map.copyOf(this.inventoryMismatches);
    }

    public Map<BlockPos, BlockMismatch> hiddenBlockMismatches()
    {
        return Map.copyOf(this.hiddenBlockMismatches);
    }

    public void rememberHiddenBlockMismatches(Map<BlockPos, BlockMismatch> hidden)
    {
        this.hiddenBlockMismatches.putAll(hidden);
    }

    public void hideInventoryMismatch(BlockMismatch mismatch)
    {
        BlockPos position = VerifierMismatchMetadata.inventoryPosition(mismatch);

        if (position == null)
        {
            return;
        }

        this.ignoredInventoryPositions.add(position);
        BlockMismatch removed = this.inventoryMismatches.remove(position);

        if (removed != null)
        {
            this.hiddenInventoryMismatches.put(position, removed);
        }
    }

    public void restoreHiddenInventoryMismatches()
    {
        this.ignoredInventoryPositions.clear();
        this.inventoryMismatches.putAll(this.hiddenInventoryMismatches);
        this.hiddenInventoryMismatches.clear();
    }

    public void clearHiddenBlockMismatches()
    {
        this.hiddenBlockMismatches.clear();
    }

    public boolean hasHiddenMismatches()
    {
        return !this.hiddenBlockMismatches.isEmpty() ||
               !this.hiddenInventoryMismatches.isEmpty() ||
               !this.ignoredInventoryPositions.isEmpty();
    }

    public void setChangeInfoHud(boolean enabled)
    {
        this.changeInfoHud = enabled;
    }

    public boolean changeInfoHud()
    {
        return this.changeInfoHud;
    }

    public void setExplicitSelection(Collection<MismatchRenderPos> positions)
    {
        this.explicitSelection = List.copyOf(positions);
        this.explicitSelectionEnabled = true;
    }

    public boolean explicitSelectionEnabled()
    {
        return this.explicitSelectionEnabled;
    }

    public List<MismatchRenderPos> sortedExplicitSelection(BlockPos center, int maximum)
    {
        List<MismatchRenderPos> sorted = new ArrayList<>(this.explicitSelection);
        sorted.sort((first, second) -> Double.compare(
                first.pos().distSqr(center),
                second.pos().distSqr(center)));
        return List.copyOf(sorted.subList(0, Math.min(maximum, sorted.size())));
    }

    public synchronized boolean setRenderFilter(Collection<MismatchRenderPos> positions)
    {
        VerifierRenderFilter next = VerifierRenderFilter.active(
                this.renderFilter.revision() + 1L, positions);

        if (next.positions().equals(this.renderFilter.positions()) && this.renderFilter.active())
        {
            return false;
        }

        this.renderFilter = next;
        return true;
    }

    public synchronized boolean clearRenderFilter()
    {
        if (!this.renderFilter.active())
        {
            return false;
        }

        this.renderFilter = VerifierRenderFilter.inactive(this.renderFilter.revision() + 1L);
        return true;
    }

    public VerifierRenderFilter renderFilter()
    {
        return this.renderFilter;
    }

    public List<MismatchRenderPos> sortedRenderFilterPositions(BlockPos center, int maximum)
    {
        List<MismatchRenderPos> positions = new ArrayList<>();

        for (Map.Entry<fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType, Set<BlockPos>> entry :
                this.renderFilter.positions().entrySet())
        {
            for (BlockPos position : entry.getValue())
            {
                positions.add(new MismatchRenderPos(entry.getKey(), position));
            }
        }

        positions.sort((first, second) -> Double.compare(
                first.pos().distSqr(center),
                second.pos().distSqr(center)));
        return List.copyOf(positions.subList(0, Math.min(maximum, positions.size())));
    }

    public boolean hasInventoryMismatch(BlockPos position)
    {
        return this.inventoryMismatches.containsKey(position);
    }

    private void removeInventoryMismatch(BlockPos position)
    {
        BlockMismatch previous = this.inventoryMismatches.remove(position);

        if (previous == null)
        {
            previous = this.hiddenInventoryMismatches.remove(position);
        }

        if (previous != null)
        {
            VerifierMismatchMetadata.remove(previous);
        }
    }
}
