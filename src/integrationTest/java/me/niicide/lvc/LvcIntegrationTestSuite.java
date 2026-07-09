package me.niicide.lvc;

final class LvcIntegrationTestSuite
{
    private LvcIntegrationTestSuite()
    {
    }

    static void runAll() throws Exception
    {
        LvcRepositoryIntegrationTest.runAll();
        LvcFriendlyErrorsIntegrationTest.runAll();
        LvcOperationRecoveryIntegrationTest.runAll();
        LvcSemanticStorageIntegrationTest.runAll();
    }
}
