package me.arnavpmr.lvc.gui.widgets;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Group;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.arnavpmr.lvc.diff.LvcVerifierDiffGroups.Entry;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryPreview;

public interface LvcChangeViewerView
{
    List<Group<Entry>> spatialGroups();

    boolean isGroupVisible(Group<Entry> group);

    boolean isGroupExpanded(BlockPos groupAnchor);

    boolean isKindExpanded(BlockPos groupAnchor, Kind kind);

    boolean isKindVisible(Kind kind);

    void toggleExpanded(LvcChangeEntry entry);

    SchematicVerifier verifier();

    void hideMismatch(BlockMismatch mismatch);

    @Nullable
    VerifierInventoryPreview lockedInventoryPreview();
}
