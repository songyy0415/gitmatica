package me.arnavpmr.lvc.gui.widgets;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Group;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.arnavpmr.lvc.diff.LvcVerifierDiffGroups.Entry;
import me.arnavpmr.lvc.overlay.LvcTrackingOverlayService;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.malilib.util.StringUtils;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierInventoryPreview;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierMismatchMetadata;

public record LvcChangeEntry(Type type, @Nullable BlockPos groupAnchor, int groupNumber,
                             @Nullable Kind kind, @Nullable Entry data,
                             @Nullable String label, int count)
{
    public static LvcChangeEntry group(Group<Entry> group, int groupNumber)
    {
        return new LvcChangeEntry(Type.GROUP, group.anchor(), groupNumber, null, null,
                StringUtils.translate("gitmatica.gui.label.lvc_change_viewer.group", groupNumber), 0);
    }

    public static LvcChangeEntry kind(Group<Entry> group, int groupNumber, Kind kind)
    {
        return new LvcChangeEntry(Type.KIND, group.anchor(), groupNumber, kind, null,
                kindDisplayName(kind), mismatchCount(group, kind));
    }

    public static LvcChangeEntry data(Group<Entry> group, int groupNumber, Kind kind, Entry data)
    {
        return new LvcChangeEntry(Type.DATA, group.anchor(), groupNumber, kind, data, null,
                data.mismatch().count());
    }

    public static LvcChangeEntry empty(String label)
    {
        return new LvcChangeEntry(Type.EMPTY, null, 0, null, null, label, 0);
    }

    public static LvcChangeEntry header()
    {
        return new LvcChangeEntry(Type.HEADER, null, 0, null, null, null, 0);
    }

    public static String kindDisplayName(Kind kind)
    {
        String translationKey = switch (kind)
        {
            case INVENTORIES_CHANGED -> "gitmatica.gui.label.lvc_change_viewer.inventories";
            case BLOCKS_ADDED -> "gitmatica.gui.label.lvc_change_viewer.added";
            case BLOCKS_REMOVED -> "gitmatica.gui.label.lvc_change_viewer.removed";
            case BLOCKS_CHANGED -> "gitmatica.gui.label.lvc_change_viewer.changed_blocks";
            case BLOCKSTATE_CHANGED -> "gitmatica.gui.label.lvc_change_viewer.changed_states";
        };
        return StringUtils.translate(translationKey);
    }

    public static int kindTextColor(Kind kind)
    {
        MismatchType mismatchType = switch (kind)
        {
            case INVENTORIES_CHANGED -> MismatchType.WRONG_STATE;
            case BLOCKS_ADDED -> MismatchType.EXTRA;
            case BLOCKS_REMOVED -> MismatchType.MISSING;
            case BLOCKS_CHANGED -> MismatchType.WRONG_BLOCK;
            case BLOCKSTATE_CHANGED -> MismatchType.WRONG_STATE;
        };
        return LvcTrackingOverlayService.semanticTrackingMismatchColor(mismatchType).intValue | 0xFF000000;
    }

    public static int mismatchCount(Group<Entry> group, Kind kind)
    {
        int count = 0;

        for (Entry entry : group.entries(kind))
        {
            count += entry.mismatch().count();
        }

        return count;
    }

    @Nullable
    public BlockMismatch mismatch()
    {
        return this.data != null ? this.data.mismatch() : null;
    }

    @Nullable
    public VerifierInventoryPreview inventoryPreview()
    {
        BlockMismatch mismatch = this.mismatch();
        return this.type == Type.DATA && this.kind == Kind.INVENTORIES_CHANGED && mismatch != null
                ? VerifierMismatchMetadata.inventoryPreview(mismatch)
                : null;
    }

    public enum Type
    {
        HEADER,
        GROUP,
        KIND,
        DATA,
        EMPTY
    }
}
