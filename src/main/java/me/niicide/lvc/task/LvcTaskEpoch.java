package me.niicide.lvc.task;

import javax.annotation.Nullable;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

record LvcTaskEpoch(long value)
{
    static LvcTaskEpoch capture()
    {
        return new LvcTaskEpoch(LvcTaskRegistry.currentWorldEpoch());
    }

    boolean isCurrent()
    {
        return LvcTaskRegistry.isCurrentWorldEpoch(this.value);
    }

    @Nullable
    ICompletionListener guard(@Nullable ICompletionListener listener)
    {
        if (listener == null)
        {
            return null;
        }

        return new ICompletionListener()
        {
            @Override
            public void onTaskCompleted()
            {
                if (isCurrent())
                {
                    listener.onTaskCompleted();
                }
            }

            @Override
            public void onTaskAborted()
            {
                if (isCurrent())
                {
                    listener.onTaskAborted();
                }
            }
        };
    }
}
