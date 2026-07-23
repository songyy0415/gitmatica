package me.niicide.lvc;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import me.niicide.lvc.config.LvcConfigs;
import me.niicide.lvc.task.LvcOperationHandle;

public final class LvcDiagnostics
{
    private static final Logger LOGGER = LogManager.getLogger(LvcReference.MOD_ID);

    private LvcDiagnostics()
    {
    }

    public static boolean isDebugEnabled()
    {
        return LvcConfigs.isDebugLoggingEnabled();
    }

    public static void debug(String message, Object... args)
    {
        if (isDebugEnabled())
        {
            LOGGER.info("[DEBUG] " + message, args);
        }
    }

    public static void debug(LvcOperationHandle handle, String message, Object... args)
    {
        if (!isDebugEnabled())
        {
            return;
        }

        Object[] taggedArgs = new Object[args.length + 1];
        taggedArgs[0] = operationTag(handle);
        System.arraycopy(args, 0, taggedArgs, 1, args.length);
        LOGGER.info("[DEBUG] {} " + message, taggedArgs);
    }

    public static void info(String message, Object... args)
    {
        LOGGER.info(message, args);
    }

    public static void info(LvcOperationHandle handle, String message, Object... args)
    {
        Object[] taggedArgs = new Object[args.length + 1];
        taggedArgs[0] = operationTag(handle);
        System.arraycopy(args, 0, taggedArgs, 1, args.length);
        LOGGER.info("{} " + message, taggedArgs);
    }

    public static void warn(String message, Object... args)
    {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Object... args)
    {
        LOGGER.error(message, args);
    }

    public static void operationAcquired(LvcOperationHandle handle)
    {
        debug("operation acquired {}", operationTag(handle));
    }

    public static void operationReleased(LvcOperationHandle handle)
    {
        debug("operation released {}", operationTag(handle));
    }

    public static void taskCreated(LvcOperationHandle handle, String taskName)
    {
        debug("task created {} task={}", operationTag(handle), taskName);
    }

    public static void taskCompleted(LvcOperationHandle handle, String taskName)
    {
        debug("task completed {} task={}", operationTag(handle), taskName);
    }

    public static void taskAborted(LvcOperationHandle handle, String taskName)
    {
        debug("task aborted {} task={}", operationTag(handle), taskName);
    }

    public static void taskFailed(LvcOperationHandle handle, String taskName, Exception failure)
    {
        LOGGER.error("task failed {} task={}", operationTag(handle), taskName, failure);
    }

    public static String operationTag(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");
        return "op=" + shortId(handle.id()) + " name=\"" + handle.name() + "\" repo=\"" + normalize(handle.repositoryDirectory()) + "\"";
    }

    private static String shortId(UUID id)
    {
        return id.toString().substring(0, 8);
    }

    private static String normalize(Path path)
    {
        return path.toAbsolutePath().normalize().toString();
    }

}
