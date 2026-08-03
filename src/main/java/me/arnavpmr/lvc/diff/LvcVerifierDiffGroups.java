package me.arnavpmr.lvc.diff;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.VerifierResultSorter;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Group;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.Kind;
import me.arnavpmr.lvc.diff.LvcSpatialDiffGroups.PositionedChange;
import me.arnavpmr.lvc.integration.litematica.verifier.GitmaticaVerifier;
import me.arnavpmr.lvc.integration.litematica.verifier.GitmaticaVerifiers;
import me.arnavpmr.lvc.integration.litematica.verifier.VerifierMismatchMetadata;

public final class LvcVerifierDiffGroups
{
    private LvcVerifierDiffGroups()
    {
    }

    public static List<Group<Entry>> build(SchematicVerifier verifier)
    {
        GitmaticaVerifier extension = GitmaticaVerifiers.extension(verifier);
        List<PositionedChange<PositionedMismatch>> changes = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockMismatch> entry :
                extension.gitmatica$getBlockMismatchesByPosition().entrySet())
        {
            changes.add(positionedChange(entry.getKey(), kindFor(entry.getValue()), entry.getValue()));
        }

        for (Map.Entry<BlockPos, BlockMismatch> entry :
                extension.gitmatica$getInventoryMismatchesByPosition().entrySet())
        {
            changes.add(positionedChange(entry.getKey(), Kind.INVENTORIES_CHANGED, entry.getValue()));
        }

        Map<MismatchKey, BlockMismatch> overviewByKey = mismatchOverviewByKey(verifier);
        VerifierResultSorter sorter = new VerifierResultSorter(verifier);
        List<Group<Entry>> result = new ArrayList<>();

        for (Group<PositionedMismatch> group : LvcSpatialDiffGroups.build(changes))
        {
            result.add(aggregate(group, overviewByKey, sorter));
        }

        return List.copyOf(result);
    }

    private static PositionedChange<PositionedMismatch> positionedChange(
            BlockPos position,
            Kind kind,
            BlockMismatch mismatch)
    {
        return new PositionedChange<>(position, kind, new PositionedMismatch(position, mismatch));
    }

    private static Group<Entry> aggregate(
            Group<PositionedMismatch> group,
            Map<MismatchKey, BlockMismatch> overviewByKey,
            VerifierResultSorter sorter)
    {
        EnumMap<Kind, List<Entry>> entries = emptyEntries();

        for (PositionedMismatch positioned : group.entries(Kind.INVENTORIES_CHANGED))
        {
            entries.get(Kind.INVENTORIES_CHANGED)
                    .add(new Entry(positioned.mismatch(), List.of(positioned.position())));
        }

        for (Kind kind : Kind.values())
        {
            if (kind != Kind.INVENTORIES_CHANGED)
            {
                LinkedHashMap<MismatchKey, MismatchAggregate> aggregates = new LinkedHashMap<>();

                for (PositionedMismatch positioned : group.entries(kind))
                {
                    BlockMismatch mismatch = positioned.mismatch();
                    MismatchKey key = MismatchKey.of(mismatch);
                    aggregates.computeIfAbsent(key, ignored -> new MismatchAggregate(mismatch))
                            .addPosition(positioned.position());
                }

                for (Map.Entry<MismatchKey, MismatchAggregate> entry : aggregates.entrySet())
                {
                    entries.get(kind).add(entry.getValue().toEntry(overviewByKey.get(entry.getKey())));
                }
            }

            entries.get(kind).sort((first, second) -> sorter.compare(first.mismatch(), second.mismatch()));
        }

        return new Group<>(group.anchor(), entries);
    }

    private static EnumMap<Kind, List<Entry>> emptyEntries()
    {
        EnumMap<Kind, List<Entry>> entries = new EnumMap<>(Kind.class);

        for (Kind kind : Kind.values())
        {
            entries.put(kind, new ArrayList<>());
        }

        return entries;
    }

    private static Map<MismatchKey, BlockMismatch> mismatchOverviewByKey(SchematicVerifier verifier)
    {
        Map<MismatchKey, BlockMismatch> result = new HashMap<>();

        for (MismatchType type : List.of(
                MismatchType.EXTRA,
                MismatchType.MISSING,
                MismatchType.WRONG_BLOCK,
                MismatchType.DIFF_BLOCK,
                MismatchType.WRONG_STATE))
        {
            for (BlockMismatch mismatch : verifier.getMismatchOverviewFor(type))
            {
                result.put(MismatchKey.of(mismatch), mismatch);
            }
        }

        return result;
    }

    private static Kind kindFor(BlockMismatch mismatch)
    {
        if (VerifierMismatchMetadata.isInventoryMismatch(mismatch))
        {
            return Kind.INVENTORIES_CHANGED;
        }

        return switch (mismatch.mismatchType())
        {
            case EXTRA -> Kind.BLOCKS_ADDED;
            case MISSING -> Kind.BLOCKS_REMOVED;
            case WRONG_BLOCK, DIFF_BLOCK -> Kind.BLOCKS_CHANGED;
            case WRONG_STATE -> mismatch.stateExpected().getBlock() == mismatch.stateFound().getBlock()
                    ? Kind.BLOCKSTATE_CHANGED
                    : Kind.BLOCKS_CHANGED;
            default -> throw new IllegalArgumentException(
                    "Unsupported block mismatch type: " + mismatch.mismatchType());
        };
    }

    private record MismatchKey(MismatchType type, BlockState expected, BlockState found)
    {
        static MismatchKey of(BlockMismatch mismatch)
        {
            return new MismatchKey(
                    mismatch.mismatchType(),
                    mismatch.stateExpected(),
                    mismatch.stateFound());
        }
    }

    public record Entry(BlockMismatch mismatch, List<BlockPos> positions)
    {
        public Entry
        {
            positions = positions.stream().map(BlockPos::immutable).toList();
        }
    }

    private record PositionedMismatch(BlockPos position, BlockMismatch mismatch)
    {
        private PositionedMismatch
        {
            position = position.immutable();
        }
    }

    private static final class MismatchAggregate
    {
        private final BlockMismatch source;
        private final List<BlockPos> positions = new ArrayList<>();

        private MismatchAggregate(BlockMismatch source)
        {
            this.source = source;
        }

        private void addPosition(BlockPos position)
        {
            this.positions.add(position.immutable());
        }

        private Entry toEntry(BlockMismatch overview)
        {
            BlockMismatch representative = overview != null && overview.count() == this.positions.size()
                    ? overview
                    : VerifierMismatchMetadata.copyWithCount(this.source, this.positions.size());
            return new Entry(representative, this.positions);
        }
    }
}
