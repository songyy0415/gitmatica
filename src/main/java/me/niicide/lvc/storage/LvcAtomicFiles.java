package me.niicide.lvc.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

public final class LvcAtomicFiles
{
    private LvcAtomicFiles()
    {
    }

    public static void writeUtf8(Path path, String contents) throws IOException
    {
        Objects.requireNonNull(path, "path");
        Path temp = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        writeUtf8(path, temp, contents);
    }

    public static void writeUtf8(Path path, Path temp, String contents) throws IOException
    {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(temp, "temp");
        Objects.requireNonNull(contents, "contents");

        Path parent = path.getParent();

        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        boolean moved = false;

        try
        {
            writeAndSync(temp, contents.getBytes(StandardCharsets.UTF_8));
            moveReplacing(temp, path);
            moved = true;

            if (parent != null)
            {
                forceDirectory(parent);
            }
        }
        finally
        {
            if (!moved)
            {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static void moveReplacing(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void forceFile(Path path)
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (FileNotFoundException ignored)
        {
        }
        catch (IOException | UnsupportedOperationException ignored)
        {
        }
    }

    public static void forceDirectory(Path directory)
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (FileNotFoundException ignored)
        {
        }
        catch (IOException | UnsupportedOperationException ignored)
        {
        }
    }

    private static void writeAndSync(Path path, byte[] bytes) throws IOException
    {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining())
            {
                channel.write(buffer);
            }

            channel.force(true);
        }
    }
}
