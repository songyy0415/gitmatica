package me.niicide.lvc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import me.niicide.lvc.model.LvcChunk;

public final class LvcChunkCodec
{
    public static final String STORAGE_MODE = "raw";

    private static final byte[] STORAGE_MAGIC = new byte[] { 'L', 'V', 'C', 'C', 'H', 'R', '1', 0 };
    public static final int STORAGE_HEADER_LENGTH = STORAGE_MAGIC.length;
    private static final byte[] CONTENT_MAGIC = new byte[] { 'L', 'V', 'C', 'C', 'H', 'N', '3', 0 };

    private LvcChunkCodec()
    {
    }

    public static byte[] encode(LvcChunk chunk) throws IOException
    {
        return encodeStorageBytes(encodeHashContent(chunk));
    }

    public static byte[] encodeHashContent(LvcChunk chunk) throws IOException
    {
        Objects.requireNonNull(chunk, "chunk");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes))
        {
            out.write(CONTENT_MAGIC);
            out.writeShort(0);
            out.writeShort(chunk.sizeX());
            out.writeShort(chunk.sizeY());
            out.writeShort(chunk.sizeZ());

            byte[] maskBytes = maskToBytes(chunk.trackedMask(), chunk.volume());
            out.writeShort(maskBytes.length);
            out.write(maskBytes);

            writeVarUInt(out, chunk.palette().size());

            for (String entry : chunk.palette())
            {
                writeString(out, entry);
            }

            for (int index : chunk.blockStateIndices())
            {
                writeVarUInt(out, index);
            }

            writeBlockEntities(out, chunk.blockEntities());
            writeEntities(out, chunk.entities());
        }

        return bytes.toByteArray();
    }

    public static byte[] encodeStorageBytes(byte[] hashContentBytes) throws IOException
    {
        Objects.requireNonNull(hashContentBytes, "hashContentBytes");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(STORAGE_MAGIC);
        bytes.write(hashContentBytes);

        return bytes.toByteArray();
    }

    public static byte[] encodeTrackedContent(LvcChunk chunk) throws IOException
    {
        return encodeTrackedContent(chunk, true);
    }

    public static byte[] encodeTrackedBlockStateContent(LvcChunk chunk) throws IOException
    {
        return encodeTrackedContent(chunk, false);
    }

    private static byte[] encodeTrackedContent(LvcChunk chunk, boolean includeBlockEntities) throws IOException
    {
        Objects.requireNonNull(chunk, "chunk");
        int[] blockStateIndices = chunk.blockStateIndices();
        List<String> trackedBlockStates = new ArrayList<>(blockStateIndices.length);

        for (int index : blockStateIndices)
        {
            trackedBlockStates.add(canonicalTrackedBlockState(chunk.palette().get(index)));
        }

        return encodeHashContent(LvcChunk.fromTrackedContent(
                chunk.sizeX(),
                chunk.sizeY(),
                chunk.sizeZ(),
                chunk.trackedMask(),
                trackedBlockStates,
                includeBlockEntities ? chunk.blockEntities() : List.of()
        ));
    }

    public static String canonicalTrackedBlockState(String blockState)
    {
        Objects.requireNonNull(blockState, "blockState");

        if (blockState.equals("minecraft:cave_air") || blockState.equals("minecraft:void_air"))
        {
            return "minecraft:air";
        }

        return blockState;
    }

    public static LvcChunk decode(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");
        return decodeHashContent(readStorageBytes(bytes));
    }

    private static LvcChunk decodeHashContent(byte[] bytes) throws IOException
    {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        byte[] magic = in.readNBytes(CONTENT_MAGIC.length);

        if (magic.length != CONTENT_MAGIC.length || !Arrays.equals(CONTENT_MAGIC, magic))
        {
            throw new IOException("Invalid LVC chunk content magic");
        }

        int flags = in.readUnsignedShort();

        if (flags != 0)
        {
            throw new IOException("Unsupported LVC chunk flags: " + flags);
        }

        int sizeX = in.readUnsignedShort();
        int sizeY = in.readUnsignedShort();
        int sizeZ = in.readUnsignedShort();
        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        int expectedMaskLength = (volume + 7) / 8;
        int maskLength = in.readUnsignedShort();

        if (maskLength != expectedMaskLength)
        {
            throw new IOException("Invalid LVC chunk tracked mask length: " + maskLength);
        }

        BitSet trackedMask = bytesToMask(in.readNBytes(maskLength), volume);
        int paletteCount = readVarUInt(in);
        List<String> palette = new ArrayList<>(paletteCount);

        for (int i = 0; i < paletteCount; i++)
        {
            palette.add(readString(in));
        }

        int trackedCount = trackedMask.cardinality();
        int[] blockStateIndices = new int[trackedCount];

        for (int i = 0; i < trackedCount; i++)
        {
            blockStateIndices[i] = readVarUInt(in);
        }

        List<LvcChunk.BlockEntityRecord> blockEntities = readBlockEntities(in);
        List<LvcChunk.EntityRecord> entities = readEntities(in);

        if (in.read() != -1)
        {
            throw new IOException("Trailing bytes after LVC chunk payload");
        }

        return new LvcChunk(sizeX, sizeY, sizeZ, trackedMask, palette, blockStateIndices, blockEntities,
                entities);
    }

    private static byte[] readStorageBytes(byte[] bytes) throws IOException
    {
        if (bytes.length <= STORAGE_MAGIC.length || !Arrays.equals(STORAGE_MAGIC, Arrays.copyOf(bytes, STORAGE_MAGIC.length)))
        {
            throw new IOException("Invalid LVC chunk storage magic");
        }

        return Arrays.copyOfRange(bytes, STORAGE_MAGIC.length, bytes.length);
    }

    static byte[] maskToBytes(BitSet mask, int volume)
    {
        byte[] bytes = new byte[(volume + 7) / 8];

        for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1))
        {
            if (index >= volume)
            {
                throw new IllegalArgumentException("LVC chunk tracked mask exceeds chunk volume");
            }

            bytes[index >> 3] |= (byte) (1 << (index & 7));
        }

        return bytes;
    }

    static BitSet bytesToMask(byte[] bytes, int volume) throws IOException
    {
        if (bytes.length != (volume + 7) / 8)
        {
            throw new IOException("Invalid LVC chunk tracked mask byte length");
        }

        BitSet mask = new BitSet(volume);

        for (int i = 0; i < volume; i++)
        {
            if ((bytes[i >> 3] & (1 << (i & 7))) != 0)
            {
                mask.set(i);
            }
        }

        return mask;
    }

    private static void writeBlockEntities(DataOutputStream out, List<LvcChunk.BlockEntityRecord> blockEntities) throws IOException
    {
        writeVarUInt(out, blockEntities.size());

        for (LvcChunk.BlockEntityRecord record : blockEntities)
        {
            byte[] nbt = record.canonicalNbt();
            out.writeShort(record.index());
            writeVarUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static List<LvcChunk.BlockEntityRecord> readBlockEntities(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<LvcChunk.BlockEntityRecord> records = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int index = in.readUnsignedShort();
            int nbtLength = readVarUInt(in);
            byte[] nbt = in.readNBytes(nbtLength);

            if (nbt.length != nbtLength)
            {
                throw new EOFException("Unexpected end of LVC block entity payload");
            }

            records.add(new LvcChunk.BlockEntityRecord(index, nbt));
        }

        return records;
    }

    private static void writeEntities(DataOutputStream out, List<LvcChunk.EntityRecord> entities) throws IOException
    {
        writeVarUInt(out, entities.size());

        for (LvcChunk.EntityRecord record : entities)
        {
            byte[] nbt = record.canonicalNbt();
            writeVarUInt(out, nbt.length);
            out.write(nbt);
        }
    }

    private static List<LvcChunk.EntityRecord> readEntities(DataInputStream in) throws IOException
    {
        int count = readVarUInt(in);
        List<LvcChunk.EntityRecord> records = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            int nbtLength = readVarUInt(in);
            byte[] nbt = in.readNBytes(nbtLength);

            if (nbt.length != nbtLength)
            {
                throw new EOFException("Unexpected end of LVC entity payload");
            }

            records.add(new LvcChunk.EntityRecord(nbt));
        }

        return records;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException
    {
        int length = readVarUInt(in);
        byte[] bytes = in.readNBytes(length);

        if (bytes.length != length)
        {
            throw new EOFException("Unexpected end of LVC chunk string");
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarUInt(DataOutputStream out, int value) throws IOException
    {
        if (value < 0)
        {
            throw new IllegalArgumentException("varuint must not be negative");
        }

        while ((value & ~0x7F) != 0)
        {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        out.writeByte(value);
    }

    private static int readVarUInt(DataInputStream in) throws IOException
    {
        int value = 0;
        int shift = 0;

        while (shift < 35)
        {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;

            if ((b & 0x80) == 0)
            {
                return value;
            }

            shift += 7;
        }

        throw new IOException("LVC chunk varuint is too long");
    }

}
