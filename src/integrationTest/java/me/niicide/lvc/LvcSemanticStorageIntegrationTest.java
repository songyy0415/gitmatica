package me.niicide.lvc;

final class LvcSemanticStorageIntegrationTest
{
    private LvcSemanticStorageIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        LvcSemanticChunkStorageIntegrationTest.runAll();
        LvcSemanticManifestIntegrationTest.runAll();
        LvcSemanticCaptureScanIntegrationTest.runAll();
        LvcRetiredCoverageIntegrationTest.runAll();
        LvcSemanticRepositoryLifecycleIntegrationTest.runAll();
        LvcSemanticOverlayCacheIntegrationTest.runAll();
        LvcSemanticSchematicIntegrationTest.runAll();
        LvcRemoteSparsePlannerIntegrationTest.runAll();
    }
}
