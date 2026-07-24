package me.niicide.lvc.diff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class LvcSpatialDiffGroups
{
    private static final Direction[] NEIGHBORS = Direction.values();
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos position) -> position.getX())
            .thenComparingInt(position -> position.getY())
            .thenComparingInt(position -> position.getZ());

    private LvcSpatialDiffGroups()
    {
    }

    public static <T> List<Group<T>> build(List<PositionedChange<T>> changes)
    {
        Map<BlockPos, List<PositionedChange<T>>> changesByPosition = new HashMap<>();

        for (PositionedChange<T> change : changes)
        {
            changesByPosition.computeIfAbsent(change.position(), ignored -> new ArrayList<>()).add(change);
        }

        List<Group<T>> groups = new ArrayList<>();

        for (List<BlockPos> positions : connectedComponents(changesByPosition.keySet()))
        {
            EnumMap<Kind, List<T>> entries = emptyEntries();

            for (BlockPos position : positions)
            {
                for (PositionedChange<T> change : changesByPosition.getOrDefault(position, List.of()))
                {
                    entries.get(change.kind()).add(change.value());
                }
            }

            groups.add(new Group<>(positions.get(0), entries));
        }

        return List.copyOf(groups);
    }

    static List<List<BlockPos>> connectedComponents(Set<BlockPos> positions)
    {
        List<BlockPos> orderedPositions = new ArrayList<>(positions);
        orderedPositions.sort(POSITION_ORDER);
        Set<BlockPos> unvisited = new HashSet<>(positions);
        List<List<BlockPos>> components = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos seed : orderedPositions)
        {
            if (unvisited.remove(seed) == false)
            {
                continue;
            }

            List<BlockPos> component = new ArrayList<>();
            queue.add(seed);

            while (queue.isEmpty() == false)
            {
                BlockPos position = queue.removeFirst();
                component.add(position);

                for (Direction direction : NEIGHBORS)
                {
                    BlockPos neighbor = position.relative(direction);

                    if (unvisited.remove(neighbor))
                    {
                        queue.addLast(neighbor);
                    }
                }
            }

            component.sort(POSITION_ORDER);
            components.add(List.copyOf(component));
        }

        return List.copyOf(components);
    }

    private static <T> EnumMap<Kind, List<T>> emptyEntries()
    {
        EnumMap<Kind, List<T>> entries = new EnumMap<>(Kind.class);

        for (Kind kind : Kind.values())
        {
            entries.put(kind, new ArrayList<>());
        }

        return entries;
    }

    public enum Kind
    {
        INVENTORIES_CHANGED,
        BLOCKS_ADDED,
        BLOCKS_REMOVED,
        BLOCKS_CHANGED,
        BLOCKSTATE_CHANGED
    }

    public record PositionedChange<T>(BlockPos position, Kind kind, T value)
    {
        public PositionedChange
        {
            position = position.immutable();
        }
    }

    public record Group<T>(BlockPos anchor, Map<Kind, List<T>> entries)
    {
        public Group
        {
            anchor = anchor.immutable();
            EnumMap<Kind, List<T>> copy = new EnumMap<>(Kind.class);

            for (Kind kind : Kind.values())
            {
                copy.put(kind, List.copyOf(entries.getOrDefault(kind, List.of())));
            }

            entries = Map.copyOf(copy);
        }

        public List<T> entries(Kind kind)
        {
            return this.entries.getOrDefault(kind, List.of());
        }

        public List<T> allEntries()
        {
            return this.allEntries(kind -> true);
        }

        public List<T> allEntries(Predicate<Kind> kindFilter)
        {
            List<T> result = new ArrayList<>();

            for (Kind kind : Kind.values())
            {
                if (kindFilter.test(kind))
                {
                    result.addAll(this.entries(kind));
                }
            }

            return result;
        }
    }
}
