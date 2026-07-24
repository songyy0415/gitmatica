package me.niicide.lvc.task;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.niicide.lvc.LvcDiagnostics;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import me.niicide.lvc.integration.litematica.task.GitmaticaTaskSchedulers;

public final class LvcTaskRegistry
{
    private static LvcOperationHandle activeOperation;
    private static Runnable activeAbortCleanup;
    private static final Map<BackgroundOperationKey, LvcOperationHandle> activeBackgroundOperations = new HashMap<>();
    private static long worldEpoch;
    private static boolean worldUnloadInProgress;

    private LvcTaskRegistry()
    {
    }

    public static synchronized Optional<LvcOperationHandle> tryAcquire(String name, Path repositoryDirectory)
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (worldUnloadInProgress || activeOperation != null || activeBackgroundOperations.isEmpty() == false)
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

        if (worldUnloadInProgress || activeOperation != null || activeBackgroundOperations.isEmpty() == false)
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
        long nextWorldEpoch;

        synchronized (LvcTaskRegistry.class)
        {
            worldUnloadInProgress = true;
            nextWorldEpoch = ++worldEpoch;
            handle = activeOperation;
            cleanup = activeAbortCleanup;
            activeOperation = null;
            activeAbortCleanup = null;
            backgroundOperationCount = activeBackgroundOperations.size();
            activeBackgroundOperations.clear();
        }

        try
        {
            if (handle != null)
            {
                LvcDiagnostics.info(handle, "aborting active operation because the world is unloading epoch={}", nextWorldEpoch);
            }
            else if (backgroundOperationCount > 0)
            {
                LvcDiagnostics.debug("aborting {} background LVC operation(s) because the world is unloading epoch={}",
                        backgroundOperationCount, nextWorldEpoch);
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

            try
            {
                GitmaticaTaskSchedulers.removeTasksIf(
                        TaskScheduler.getInstanceClient(), LvcTaskRegistry::isLvcTask);
            }
            finally
            {
                GitmaticaTaskSchedulers.removeTasksIf(
                        TaskScheduler.getInstanceServer(), LvcTaskRegistry::isLvcTask);
            }
        }
        finally
        {
            synchronized (LvcTaskRegistry.class)
            {
                worldUnloadInProgress = false;
            }
        }
    }

    static synchronized long currentWorldEpoch()
    {
        return worldEpoch;
    }

    static synchronized boolean isCurrentWorldEpoch(long epoch)
    {
        return epoch == worldEpoch;
    }

    private static boolean isLvcTask(Object task)
    {
        return task instanceof LvcWorldTask;
    }

    public static synchronized boolean hasActiveOperation()
    {
        return worldUnloadInProgress || activeOperation != null || activeBackgroundOperations.isEmpty() == false;
    }

    public static synchronized String activeOperationName()
    {
        if (activeOperation != null)
        {
            return displayOperationName(activeOperation.name());
        }

        if (worldUnloadInProgress)
        {
            return "World unload";
        }

        return activeBackgroundOperations.values().stream()
                .findFirst()
                .map(LvcOperationHandle::name)
                .map(LvcTaskRegistry::displayOperationName)
                .orElse("");
    }

    private static String displayOperationName(String name)
    {
        return name.startsWith("LVC ") ? name.substring(4) : name;
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
