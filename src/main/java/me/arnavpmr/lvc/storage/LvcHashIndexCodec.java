package me.arnavpmr.lvc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.arnavpmr.lvc.model.LvcChunkCoordinate;
import me.arnavpmr.lvc.model.LvcContentFormat;

public final class LvcHashIndexCodec
{
    public static final String FORMAT = LvcContentFormat.HASH_INDEX_FORMAT;
    public static final String INDEXES_DIRECTORY = LvcContentFormat.HASH_INDEX_DIRECTORY;
    public static final String EXTENSION = LvcContentFormat.HASH_INDEX_EXTENSION;
    public static final String STORAGE_MODE = "raw";

    private static final byte[] MAGIC = new byte[] { 'L', 'V', 'C', 'I', 'D', 'R', '1', 0 };
    private static final int HASH_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private LvcHashIndexCodec()
    {
    }

    public static String defaultIndexPath(String siteId)
    {
        return LvcContentFormat.defaultHashIndexPath(siteId);
    }

    public static void write(Path path, Map<String, String> fullHashes, Map<String, String> trackedHashes) throws IOException
    {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();

        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        Files.write(path, encode(fullHashes, trackedHashes));
    }

    public static HashRefs read(Path path) throws IOException
    {
        Objects.requireNonNull(path, "path");
        return decode(Files.readAllBytes(path));
    }

    public static byte[] encode(Map<String, String> fullHashes, Map<String, String> trackedHashes) throws IOException
    {
        Objects.requireNonNull(fullHashes, "fullHashes");
        Objects.requireNonNull(trackedHashes, "trackedHashes");

        if (!fullHashes.keySet().equals(trackedHashes.keySet()))
        {
            throw new IllegalArgumentException("LVC hash index full/tracked hash keys must match");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(MAGIC);

        try (DataOutputStream out = new DataOutputStream(bytes))
        {
            TreeMap<LvcChunkCoordinate, HashPair> sorted = sortRefs(fullHashes, trackedHashes);
            writeVarUInt(out, sorted.size());

            int previousX = 0;
            int previousY = 0;
            int previousZ = 0;

            for (Map.Entry<LvcChunkCoordinate, HashPair> entry : sorted.entrySet())
            {
                LvcChunkCoordinate coordinate = entry.getKey();
                writeVarInt(out, coordinate.x() - previousX);
                writeVarInt(out, coordinate.y() - previousY);
                writeVarInt(out, coordinate.z() - previousZ);
                out.write(objectHashBytes(entry.getValue().fullHash()));
                out.write(objectHashBytes(entry.getValue().trackedHash()));
                previousX = coordinate.x();
                previousY = coordinate.y();
                previousZ = coordinate.z();
            }
        }

        return bytes.toByteArray();
    }

    public static HashRefs decode(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");

        if (bytes.length < MAGIC.length || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length)))
        {
            throw new IOException("Invalid LVC hash index magic");
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, MAGIC.length, bytes.length - MAGIC.length)))
        {
            int count = readVarUInt(in);
            TreeMap<String, String> fullHashes = new TreeMap<>();
            TreeMap<String, String> trackedHashes = new TreeMap<>();
            int previousX = 0;
            int previousY = 0;
            int previousZ = 0;

            for (int i = 0; i < count; i++)
            {
                int x = previousX + readVarInt(in);
                int y = previousY + readVarInt(in);
                int z = previousZ + readVarInt(in);
                LvcChunkCoordinate coordinate = new LvcChunkCoordinate(x, y, z);
                String key = coordinate.key();
                String fullHash = objectId(readHashBytes(in));
                String trackedHash = objectId(readHashBytes(in));

                if (fullHashes.put(key, fullHash) != null || trackedHashes.put(key, trackedHash) != null)
                {
                    throw new IOException("Duplicate LVC hash index coordinate: " + key);
                }

                previousX = x;
                previousY = y;
                previousZ = z;
            }

            if (in.read() != -1)
            {
                throw new IOException("Trailing bytes after LVC hash index payload");
            }

            return new HashRefs(Map.copyOf(fullHashes), Map.copyOf(trackedHashes));
        }
    }

    public static String objectId(byte[] hashBytes)
    {
        Objects.requireNonNull(hashBytes, "hashBytes");

        if (hashBytes.length != HASH_BYTES)
        {
            throw new IllegalArgumentException("LVC hash index SHA-256 payload must be 32 bytes");
        }

        return LvcChunkStore.HASH_ALGORITHM + ":" + HEX.formatHex(hashBytes);
    }

    public static byte[] objectHashBytes(String objectId)
    {
        Objects.requireNonNull(objectId, "objectId");
        String prefix = LvcChunkStore.HASH_ALGORITHM + ":";

        if (!objectId.startsWith(prefix))
        {
            throw new IllegalArgumentException("LVC hash index object id must start with " + prefix);
        }

        String hex = objectId.substring(prefix.length());

        if (hex.length() != HASH_BYTES * 2 || !isLowercaseHex(hex))
        {
            throw new IllegalArgumentException("LVC hash index object id must contain a lowercase 64-character SHA-256 hex value");
        }

        return HEX.parseHex(hex);
    }

    private static TreeMap<LvcChunkCoordinate, HashPair> sortRefs(Map<String, String> fullHashes, Map<String, String> trackedHashes)
    {
        TreeMap<LvcChunkCoordinate, HashPair> sorted = new TreeMap<>();

        for (Map.Entry<String, String> entry : fullHashes.entrySet())
        {
            LvcChunkCoordinate coordinate = LvcChunkCoordinate.parse(entry.getKey());
            HashPair previous = sorted.put(coordinate, new HashPair(entry.getValue(), trackedHashes.get(entry.getKey())));

            if (previous != null)
            {
                throw new IllegalArgumentException("Duplicate LVC hash index coordinate: " + entry.getKey());
            }
        }

        return sorted;
    }

    private static byte[] readHashBytes(DataInputStream in) throws IOException
    {
        byte[] bytes = in.readNBytes(HASH_BYTES);

        if (bytes.length != HASH_BYTES)
        {
            throw new EOFException("Unexpected end of LVC hash index SHA-256 payload");
        }

        return bytes;
    }

    private static void writeVarUInt(DataOutputStream out, int value) throws IOException
    {
        LvcBinaryIO.writeUnsignedVarInt(out, value);
    }

    private static int readVarUInt(DataInputStream in) throws IOException
    {
        return LvcBinaryIO.readUnsignedVarInt(in, "LVC hash index");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException
    {
        LvcBinaryIO.writeSignedVarInt(out, value);
    }

    private static int readVarInt(DataInputStream in) throws IOException
    {
        return LvcBinaryIO.readSignedVarInt(in, "LVC hash index");
    }

    private static boolean isLowercaseHex(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);

            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            {
                return false;
            }
        }

        return true;
    }

    public record HashRefs(Map<String, String> fullHashes, Map<String, String> trackedHashes)
    {
        public HashRefs
        {
            fullHashes = Map.copyOf(fullHashes);
            trackedHashes = Map.copyOf(trackedHashes);

            if (!fullHashes.keySet().equals(trackedHashes.keySet()))
            {
                throw new IllegalArgumentException("LVC hash index full/tracked hash keys must match");
            }
        }
    }

    private record HashPair(String fullHash, String trackedHash)
    {
    }
}
