package me.arnavpmr.lvc;

import me.arnavpmr.lvc.diff.LvcSpatialDiffGroupsIntegrationTest;
import fi.dy.masa.litematica.schematic.verifier.LvcVerifierHiddenMismatchIntegrationTest;

final class LvcIntegrationTestSuite
{
    private LvcIntegrationTestSuite()
    {
    }

    static void runAll() throws Exception
    {
        LvcSpatialDiffGroupsIntegrationTest.runAll();
        LvcVerifierHiddenMismatchIntegrationTest.runAll();
        LvcBlockInspectionIntegrationTest.runAll();
        LvcRepositoryIntegrationTest.runAll();
        LvcFriendlyErrorsIntegrationTest.runAll();
        LvcTaskRegistryIntegrationTest.runAll();
        LvcVerifierStartGuardIntegrationTest.runAll();
        LvcOperationRecoveryIntegrationTest.runAll();
        LvcSemanticStorageIntegrationTest.runAll();
    }
}
