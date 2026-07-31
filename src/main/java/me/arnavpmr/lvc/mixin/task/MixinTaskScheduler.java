package me.arnavpmr.lvc.mixin.task;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import me.arnavpmr.lvc.integration.litematica.task.GitmaticaTaskScheduler;

@Mixin(TaskScheduler.class)
abstract class MixinTaskScheduler implements GitmaticaTaskScheduler
{
    @Shadow @Final private List<ITask> tasks;
    @Shadow @Final private List<ITask> tasksToAdd;

    /**
     * @author Gitmatica
     * @reason LVC cancellation can remove tasks reentrantly; iterating a stable
     * snapshot prevents skipped tasks and index corruption.
     */
    @Overwrite
    public void runTasks()
    {
        if (Minecraft.getInstance().player == null)
        {
            return;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push(Reference.MOD_ID + "_run_tasks");

        synchronized (this)
        {
            for (ITask task : new ArrayList<>(this.tasks))
            {
                if (!this.tasks.contains(task))
                {
                    continue;
                }

                boolean finished = task.shouldRemove();

                if (!finished && task.canExecute() && task.getTimer().tick())
                {
                    finished = task.execute(profiler);
                }

                if (finished)
                {
                    task.stop();
                    this.tasks.remove(task);
                }
            }

            for (ITask task : this.tasksToAdd)
            {
                task.init();
                this.tasks.add(task);
            }

            this.tasksToAdd.clear();
        }

        profiler.pop();
    }

    @Override
    public void gitmatica$removeTasksIf(Predicate<ITask> predicate)
    {
        synchronized (this)
        {
            this.gitmatica$removeMatching(this.tasks, predicate);
            this.gitmatica$removeMatching(this.tasksToAdd, predicate);
        }
    }

    @Unique
    private void gitmatica$removeMatching(
            List<ITask> source, Predicate<ITask> predicate)
    {
        for (ITask task : new ArrayList<>(source))
        {
            if (source.contains(task) && predicate.test(task))
            {
                task.stop();
                source.remove(task);
            }
        }
    }
}
