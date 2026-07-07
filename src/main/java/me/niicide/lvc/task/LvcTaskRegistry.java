package me.niicide.lvc.task;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.niicide.lvc.LvcDiagnostics;
import fi.dy.masa.litematica.scheduler.TaskScheduler;

public final class LvcTaskRegistry
{
    private static LvcOperationHandle activeOperation;
    private static Runnable activeAbortCleanup;
    private static final Map<BackgroundOperationKey, LvcOperationHandle> activeBackgroundOperations = new HashMap<>();

    private LvcTaskRegistry()
    {
    }

    public static synchronized Optional<LvcOperationHandle> tryAcquire(String name, Path repositoryDirectory)
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (activeOperation != null)
        {
            return Optional.empty();
        }

        activeOperation = new LvcOperationHandle(UUID.randomUUID(), name, repositoryDirectory.toAbsolutePath().normalize());
        activeAbortCleanup = null;
        LvcDiagnostics.operationAcquired(activeOperation);
        return Optional.of(activeOperation);
    }

    public static synchronized Optional<LvcOperationHandle> tryAcquireBackground(String name, Path repositoryDirectory)
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        BackgroundOperationKey key = BackgroundOperationKey.of(name, repositoryDirectory);

        if (activeBackgroundOperations.containsKey(key))
        {
            return Optional.empty();
        }

        LvcOperationHandle handle = new LvcOperationHandle(UUID.randomUUID(), name, key.repositoryDirectory());
        activeBackgroundOperations.put(key, handle);
        LvcDiagnostics.debug("background operation acquired {}", LvcDiagnostics.operationTag(handle));
        return Optional.of(handle);
    }

    public static synchronized void release(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");

        if (activeOperation != null && activeOperation.id().equals(handle.id()))
        {
            LvcDiagnostics.operationReleased(handle);
            activeOperation = null;
            activeAbortCleanup = null;
            return;
        }

        if (activeBackgroundOperations.values().removeIf(operation -> operation.id().equals(handle.id())))
        {
            LvcDiagnostics.debug("background operation released {}", LvcDiagnostics.operationTag(handle));
        }
    }

    public static synchronized boolean isActive(LvcOperationHandle handle)
    {
        Objects.requireNonNull(handle, "handle");
        return activeOperation != null && activeOperation.id().equals(handle.id());
    }

    public static synchronized void setAbortCleanup(LvcOperationHandle handle, Runnable cleanup)
    {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(cleanup, "cleanup");

        if (activeOperation != null && activeOperation.id().equals(handle.id()))
        {
            activeAbortCleanup = cleanup;
        }
    }

    public static void abortActiveOperationForWorldUnload()
    {
        LvcOperationHandle handle;
        Runnable cleanup;
        int backgroundOperationCount;

        synchronized (LvcTaskRegistry.class)
        {
            handle = activeOperation;
            cleanup = activeAbortCleanup;
            activeAbortCleanup = null;
            backgroundOperationCount = activeBackgroundOperations.size();
        }

        if (handle == null && backgroundOperationCount == 0)
        {
            return;
        }

        if (handle != null)
        {
            LvcDiagnostics.debug(handle, "aborting active operation because the world is unloading");
        }
        else
        {
            LvcDiagnostics.debug("aborting {} background LVC operation(s) because the world is unloading",
                    backgroundOperationCount);
        }

        if (handle != null && cleanup != null)
        {
            try
            {
                cleanup.run();
            }
            catch (Exception e)
            {
                LvcDiagnostics.debug(handle, "active operation abort cleanup failed: {}", e.getMessage());
            }
        }

        boolean removedClientTask = TaskScheduler.getInstanceClient().removeTasksIf(LvcTaskRegistry::isLvcTask);
        boolean removedServerTask = TaskScheduler.getInstanceServer().removeTasksIf(LvcTaskRegistry::isLvcTask);

        if (handle != null && !removedClientTask && !removedServerTask)
        {
            release(handle);
        }

        clearBackgroundOperationsForWorldUnload();
    }

    private static synchronized void clearBackgroundOperationsForWorldUnload()
    {
        if (activeBackgroundOperations.isEmpty())
        {
            return;
        }

        LvcDiagnostics.debug("clearing {} background LVC operation handle(s) after world unload task removal",
                activeBackgroundOperations.size());
        activeBackgroundOperations.clear();
    }

    private static boolean isLvcTask(Object task)
    {
        return task instanceof LvcTaskBase<?> || task instanceof LvcAuthoritativeClientSyncTask;
    }

    public static synchronized boolean hasActiveOperation()
    {
        return activeOperation != null;
    }

    public static synchronized String activeOperationName()
    {
        return activeOperation != null ? activeOperation.name() : "";
    }

    public static synchronized Optional<LvcOperationHandle> activeOperation()
    {
        return Optional.ofNullable(activeOperation);
    }

    public static synchronized boolean hasActiveBackgroundOperation(String name, Path repositoryDirectory)
    {
        return activeBackgroundOperations.containsKey(BackgroundOperationKey.of(name, repositoryDirectory));
    }

    private record BackgroundOperationKey(String name, Path repositoryDirectory)
    {
        private static BackgroundOperationKey of(String name, Path repositoryDirectory)
        {
            return new BackgroundOperationKey(name, repositoryDirectory.toAbsolutePath().normalize());
        }
    }
}
