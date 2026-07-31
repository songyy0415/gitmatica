package me.arnavpmr.lvc;

public class LvcRepositoryIntegrationTest
{
    public static void main(String[] args) throws Exception
    {
        LvcIntegrationTestSuite.runAll();
    }

    static void runAll() throws Exception
    {
        LvcProjectRepositoryIntegrationTest.runAll();
        LvcBranchIntegrationTest.runAll();
        LvcMergeIntegrationTest.runAll();
        LvcCheckoutUndoIntegrationTest.runAll();
    }
}
