package me.arnavpmr.lvc.task;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import me.arnavpmr.lvc.LvcDiagnostics;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;

public abstract class LvcTaskBase<T> extends TaskBase implements LvcWorldTask
{
    private final LvcOperationHandle handle;
    private final LvcTaskCallbacks<T> callbacks;
    private final boolean releaseLockOnSuccess;
    private final LvcTaskEpoch taskEpoch;
    private boolean callbackDelivered;
    @Nullable private T result;
    @Nullable private Exception failure;
    private boolean succeeded;

    protected LvcTaskBase(LvcOperationHandle handle, String displayName, LvcTaskCallbacks<T> callbacks,
                          boolean releaseLockOnSuccess)
    {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.name = Objects.requireNonNull(displayName, "displayName");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.releaseLockOnSuccess = releaseLockOnSuccess;
        this.taskEpoch = LvcTaskEpoch.capture();
        InfoHud.getInstance().addInfoHudRenderer(this, true);
        LvcDiagnostics.taskCreated(handle, displayName);
    }

    protected LvcOperationHandle handle()
    {
        return this.handle;
    }

    @Override
    public boolean shouldRemove()
    {
        return !this.taskEpoch.isCurrent() || this.finished || super.shouldRemove();
    }

    protected boolean complete(T result)
    {
        this.result = result;
        this.succeeded = true;
        LvcDiagnostics.taskCompleted(this.handle, this.name);
        this.finished = true;
        return true;
    }

    protected boolean fail(Exception failure)
    {
        this.failure = Objects.requireNonNull(failure, "failure");
        LvcDiagnostics.taskFailed(this.handle, this.name, this.failure);
        this.finished = true;
        return true;
    }

    @Override
    public void stop()
    {
        boolean shouldRelease = !this.succeeded || this.releaseLockOnSuccess;

        try
        {
            this.deliverCallback(shouldRelease);
            super.stop();
        }
        finally
        {
            InfoHud.getInstance().removeInfoHudRenderer(this, false);
        }
    }

    private void deliverCallback(boolean shouldRelease)
    {
        if (this.callbackDelivered)
        {
            return;
        }

        this.callbackDelivered = true;
        Minecraft.getInstance().execute(() ->
        {
            if (!this.taskEpoch.isCurrent())
            {
                LvcDiagnostics.debug(this.handle, "suppressing stale task callback after world unload task={}", this.name);
                return;
            }

            if (shouldRelease)
            {
                LvcTaskRegistry.release(this.handle);
            }

            if (this.failure != null)
            {
                this.callbacks.failure().accept(this.failure);
            }
            else if (this.succeeded)
            {
                this.callbacks.success().accept(this.result);
            }
            else
            {
                this.callbacks.aborted().run();
                LvcDiagnostics.taskAborted(this.handle, this.name);
            }
        });
    }
}
