package me.arnavpmr.lvc.capture;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import me.arnavpmr.lvc.model.LvcIntPosition;

public final class LvcBlockStateOnlyWorldReader implements LvcWorldReader
{
    private final Level world;
    private final Map<BlockState, String> blockStateStringCache = new IdentityHashMap<>();

    public LvcBlockStateOnlyWorldReader(Level world)
    {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public boolean canReadAt(LvcIntPosition worldPos)
    {
        return this.world.hasChunk(SectionPos.blockToSectionCoord(worldPos.x()), SectionPos.blockToSectionCoord(worldPos.z()));
    }

    @Override
    public String blockStateAt(LvcIntPosition worldPos)
    {
        BlockState state = this.world.getBlockState(new BlockPos(worldPos.x(), worldPos.y(), worldPos.z()));
        String cached = this.blockStateStringCache.get(state);

        if (cached == null)
        {
            cached = LvcMinecraftWorldReader.blockStateString(state);
            this.blockStateStringCache.put(state, cached);
        }

        return cached;
    }
}
