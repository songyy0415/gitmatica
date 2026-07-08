package me.niicide.lvc.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import me.niicide.lvc.LvcDiagnostics;

public final class LvcServuxBulkEntityCache
{
    private static final int SERVUX_MIN_Y = -60;
    private static final int SERVUX_MAX_Y = 319;
    private static final Map<ChunkPos, RequestInfo> REQUESTS = new HashMap<>();
    private static final Map<ChunkPos, List<Entry>> REPLIES = new HashMap<>();
    private static final Map<ChunkPos, Map<BlockPos, CompoundTag>> BLOCK_ENTITY_REPLIES = new HashMap<>();

    private LvcServuxBulkEntityCache()
    {
    }

    public static synchronized void beginRequest(ChunkPos chunkPos, int minY, int maxY)
    {
        REQUESTS.put(chunkPos, new RequestInfo(clamp(minY), clamp(maxY)));
        REPLIES.remove(chunkPos);
        BLOCK_ENTITY_REPLIES.remove(chunkPos);
    }

    public static synchronized void recordBulkReply(CompoundTag nbt)
    {
        if (!"BulkEntityReply".equals(nbt.getStringOr("Task", "")) ||
            !nbt.contains("chunkX") ||
            !nbt.contains("chunkZ"))
        {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(nbt.getIntOr("chunkX", 0), nbt.getIntOr("chunkZ", 0));
        RequestInfo request = REQUESTS.remove(chunkPos);

        if (request == null)
        {
            return;
        }

        ListTag tiles = nbt.getListOrEmpty("TileEntities");
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>(tiles.size());

        for (int i = 0; i < tiles.size(); i++)
        {
            CompoundTag tile = tiles.getCompoundOrEmpty(i);

            if (!tile.isEmpty() && tile.contains("x") && tile.contains("y") && tile.contains("z"))
            {
                blockEntities.put(new BlockPos(tile.getIntOr("x", 0), tile.getIntOr("y", 0), tile.getIntOr("z", 0)),
                        tile.copy());
            }
        }

        ListTag entities = nbt.getListOrEmpty("Entities");
        List<Entry> entries = new ArrayList<>(entities.size());

        for (int i = 0; i < entities.size(); i++)
        {
            CompoundTag entity = entities.getCompoundOrEmpty(i);

            if (!entity.isEmpty())
            {
                entries.add(new Entry(chunkPos, request.minY(), entity.copy()));
            }
        }

        REPLIES.put(chunkPos, List.copyOf(entries));
        BLOCK_ENTITY_REPLIES.put(chunkPos, Map.copyOf(blockEntities));
        LvcDiagnostics.debug("LvcServuxBulkEntityCache: recorded Servux bulk entity reply column={} blockEntities={} entities={} minY={} maxY={}",
                chunkPos, blockEntities.size(), entries.size(), request.minY(), request.maxY());
    }

    public static synchronized boolean hasReply(ChunkPos chunkPos)
    {
        return REPLIES.containsKey(chunkPos);
    }

    public static synchronized List<Entry> entriesFor(ChunkPos chunkPos)
    {
        return REPLIES.getOrDefault(chunkPos, List.of());
    }

    @Nullable
    public static synchronized CompoundTag blockEntityAt(ChunkPos chunkPos, BlockPos pos)
    {
        CompoundTag tag = BLOCK_ENTITY_REPLIES.getOrDefault(chunkPos, Map.of()).get(pos);
        return tag == null ? null : tag.copy();
    }

    public static synchronized void clear()
    {
        REQUESTS.clear();
        REPLIES.clear();
        BLOCK_ENTITY_REPLIES.clear();
    }

    private static int clamp(int y)
    {
        return Math.max(SERVUX_MIN_Y, Math.min(SERVUX_MAX_Y, y));
    }

    public record Entry(ChunkPos chunkPos, int minY, CompoundTag nbt)
    {
        @Override
        public CompoundTag nbt()
        {
            return this.nbt.copy();
        }
    }

    private record RequestInfo(int minY, int maxY)
    {
    }
}
