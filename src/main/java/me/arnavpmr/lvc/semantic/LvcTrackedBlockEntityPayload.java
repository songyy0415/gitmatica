package me.arnavpmr.lvc.semantic;

import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import me.arnavpmr.lvc.storage.LvcCanonicalNbt;
import me.arnavpmr.lvc.storage.LvcChunkCodec;
import me.arnavpmr.lvc.storage.LvcChunkStore;

public final class LvcTrackedBlockEntityPayload
{
    private LvcTrackedBlockEntityPayload()
    {
    }

    public static boolean matches(Level world, BlockPos blockPos, @Nullable byte[] expectedNbt) throws IOException
    {
        byte[] expectedTrackedNbt = trackedContent(expectedNbt);
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return expectedTrackedNbt == null;
        }

        return Arrays.equals(trackedContent(world, blockEntity), expectedTrackedNbt);
    }

    public static String describeWorld(Level world, BlockPos blockPos) throws IOException
    {
        BlockEntity blockEntity = world.getBlockEntity(blockPos);

        if (blockEntity == null)
        {
            return "<missing block entity>";
        }

        return describeTracked(trackedContent(world, blockEntity));
    }

    public static String describeStored(@Nullable byte[] canonicalNbt) throws IOException
    {
        return describeTracked(trackedContent(canonicalNbt));
    }

    @Nullable
    private static byte[] trackedContent(@Nullable byte[] canonicalNbt) throws IOException
    {
        return canonicalNbt == null ? null : LvcChunkCodec.encodeTrackedBlockEntityContent(canonicalNbt);
    }

    @Nullable
    private static byte[] trackedContent(Level world, BlockEntity blockEntity) throws IOException
    {
        byte[] canonicalNbt = LvcCanonicalNbt.encodeBlockEntity(
                blockEntity.saveWithFullMetadata(world.registryAccess())
        );
        return trackedContent(canonicalNbt);
    }

    private static String describeTracked(@Nullable byte[] trackedNbt)
    {
        return trackedNbt == null ? "<no tracked inventory>" : LvcChunkStore.objectId(trackedNbt);
    }
}
