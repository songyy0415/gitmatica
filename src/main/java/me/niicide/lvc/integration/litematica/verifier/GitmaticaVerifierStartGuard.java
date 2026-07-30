package me.niicide.lvc.integration.litematica.verifier;

/**
 * Distinguishes Gitmatica-owned verifier starts from user-initiated Litematica
 * starts, preventing the start interception mixin from recursing.
 */
public final class GitmaticaVerifierStartGuard
{
    private static final ThreadLocal<Boolean> DIRECT_START =
            ThreadLocal.withInitial(() -> false);

    private GitmaticaVerifierStartGuard()
    {
    }

    public static boolean isDirectStart()
    {
        return DIRECT_START.get();
    }

    public static void runDirectly(Runnable action)
    {
        boolean previous = DIRECT_START.get();
        DIRECT_START.set(true);

        try
        {
            action.run();
        }
        finally
        {
            if (previous)
            {
                DIRECT_START.set(true);
            }
            else
            {
                DIRECT_START.remove();
            }
        }
    }
}
