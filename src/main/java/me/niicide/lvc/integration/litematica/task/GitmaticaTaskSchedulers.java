package me.niicide.lvc.integration.litematica.task;

import java.util.function.Predicate;

import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;

/**
 * Central adapter for scheduler capabilities supplied by the runtime mixin.
 * The public-API fallback keeps headless integration tests independent of a
 * Fabric/Mixin bootstrap.
 */
public final class GitmaticaTaskSchedulers
{
    private GitmaticaTaskSchedulers()
    {
    }

    public static void removeTasksIf(
            TaskScheduler scheduler, Predicate<ITask> predicate)
    {
        if (scheduler instanceof GitmaticaTaskScheduler extension)
        {
            extension.gitmatica$removeTasksIf(predicate);
            return;
        }

        for (ITask task : scheduler.getAllTasks())
        {
            if (predicate.test(task))
            {
                scheduler.removeTask(task);
            }
        }
    }
}
