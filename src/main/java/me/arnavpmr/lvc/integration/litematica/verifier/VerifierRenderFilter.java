package me.arnavpmr.lvc.integration.litematica.verifier;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

public record VerifierRenderFilter(
        long revision,
        boolean active,
        Map<MismatchType, Set<BlockPos>> positions)
{
    public static VerifierRenderFilter inactive(long revision)
    {
        return new VerifierRenderFilter(revision, false, Map.of());
    }

    public static VerifierRenderFilter active(long revision, Collection<MismatchRenderPos> positions)
    {
        EnumMap<MismatchType, Set<BlockPos>> byType = new EnumMap<>(MismatchType.class);

        for (MismatchRenderPos position : positions)
        {
            byType.computeIfAbsent(position.type(), ignored -> new HashSet<>())
                    .add(position.pos().immutable());
        }

        byType.replaceAll((ignored, values) -> Set.copyOf(values));
        return new VerifierRenderFilter(revision, true, Map.copyOf(byType));
    }

    public boolean includes(MismatchType type, BlockPos position)
    {
        return !this.active || this.positions.getOrDefault(type, Set.of()).contains(position);
    }
}
