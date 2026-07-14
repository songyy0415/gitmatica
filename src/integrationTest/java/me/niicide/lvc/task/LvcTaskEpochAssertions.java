package me.niicide.lvc.task;

import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class LvcTaskEpochAssertions
{
    private LvcTaskEpochAssertions()
    {
    }

    public static void assertWorldUnloadInvalidatesPreviousEpoch()
    {
        LvcTaskEpoch previousEpoch = LvcTaskEpoch.capture();
        int[] callbackCount = { 0 };
        ICompletionListener guardedListener = previousEpoch.guard(new ICompletionListener()
        {
            @Override
            public void onTaskCompleted()
            {
                callbackCount[0]++;
            }

            @Override
            public void onTaskAborted()
            {
                callbackCount[0]++;
            }
        });

        LvcTaskRegistry.abortActiveOperationForWorldUnload();

        if (previousEpoch.isCurrent())
        {
            throw new AssertionError("world unload must invalidate callbacks captured in the previous task epoch");
        }

        guardedListener.onTaskCompleted();
        guardedListener.onTaskAborted();

        if (callbackCount[0] != 0)
        {
            throw new AssertionError("callbacks from the previous task epoch must be suppressed");
        }
    }

    public static void assertSpecializedTasksAreWorldBound()
    {
        assertWorldBound(LvcSparseCommandPasteTask.class);
        assertWorldBound(LvcAuthoritativeClientSyncTask.class);
    }

    private static void assertWorldBound(Class<?> taskClass)
    {
        if (!LvcWorldTask.class.isAssignableFrom(taskClass))
        {
            throw new AssertionError(taskClass.getSimpleName() + " must participate in world unload fencing");
        }
    }
}
