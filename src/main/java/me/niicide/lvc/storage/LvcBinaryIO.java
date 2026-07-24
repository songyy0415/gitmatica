package me.niicide.lvc.storage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class LvcBinaryIO
{
    private LvcBinaryIO()
    {
    }

    static void writeUnsignedVarInt(DataOutputStream out, int value) throws IOException
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

    static int readUnsignedVarInt(DataInputStream in, String payloadName) throws IOException
    {
        int value = 0;
        int shift = 0;

        while (shift < 35)
        {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;

            if ((next & 0x80) == 0)
            {
                return value;
            }

            shift += 7;
        }

        throw new IOException(payloadName + " varuint is too long");
    }

    static void writeSignedVarInt(DataOutputStream out, int value) throws IOException
    {
        boolean more;

        do
        {
            int next = value & 0x7F;
            value >>= 7;
            more = !((value == 0 && (next & 0x40) == 0) || (value == -1 && (next & 0x40) != 0));

            if (more)
            {
                next |= 0x80;
            }

            out.writeByte(next);
        }
        while (more);
    }

    static int readSignedVarInt(DataInputStream in, String payloadName) throws IOException
    {
        int value = 0;
        int shift = 0;
        int next;

        do
        {
            if (shift >= 35)
            {
                throw new IOException(payloadName + " varint is too long");
            }

            next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            shift += 7;
        }
        while ((next & 0x80) != 0);

        if (shift < 32 && (next & 0x40) != 0)
        {
            value |= -1 << shift;
        }

        return value;
    }
}
