package me.niicide.lvc.integration.litematica.task;

import java.util.function.Predicate;

import fi.dy.masa.litematica.scheduler.ITask;

public interface GitmaticaTaskScheduler
{
    void gitmatica$removeTasksIf(Predicate<ITask> predicate);
}
