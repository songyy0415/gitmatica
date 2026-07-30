package me.niicide.lvc;

import me.niicide.lvc.integration.litematica.verifier.GitmaticaVerifierStartGuard;

final class LvcVerifierStartGuardIntegrationTest
{
    private LvcVerifierStartGuardIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("internal verifier starts are guarded",
                LvcVerifierStartGuardIntegrationTest::internalVerifierStartsAreGuarded);
        IntegrationTestSupport.run("verifier start guard restores state after failure",
                LvcVerifierStartGuardIntegrationTest::verifierStartGuardRestoresStateAfterFailure);
    }

    private static void internalVerifierStartsAreGuarded()
    {
        IntegrationTestSupport.assertTrue(!GitmaticaVerifierStartGuard.isDirectStart(),
                "verifier start guard should initially be inactive");
        GitmaticaVerifierStartGuard.runDirectly(() ->
        {
            IntegrationTestSupport.assertTrue(GitmaticaVerifierStartGuard.isDirectStart(),
                    "internal verifier start should bypass the user-start interceptor");
            GitmaticaVerifierStartGuard.runDirectly(() ->
                    IntegrationTestSupport.assertTrue(GitmaticaVerifierStartGuard.isDirectStart(),
                            "nested internal verifier start should remain guarded"));
            IntegrationTestSupport.assertTrue(GitmaticaVerifierStartGuard.isDirectStart(),
                    "nested guard should restore the outer guarded state");
        });
        IntegrationTestSupport.assertTrue(!GitmaticaVerifierStartGuard.isDirectStart(),
                "verifier start guard should clear after the internal start");
    }

    private static void verifierStartGuardRestoresStateAfterFailure()
    {
        try
        {
            GitmaticaVerifierStartGuard.runDirectly(() ->
            {
                throw new IllegalStateException("expected");
            });
        }
        catch (IllegalStateException ignored)
        {
        }

        IntegrationTestSupport.assertTrue(!GitmaticaVerifierStartGuard.isDirectStart(),
                "verifier start guard should clear when an internal start fails");
    }
}
