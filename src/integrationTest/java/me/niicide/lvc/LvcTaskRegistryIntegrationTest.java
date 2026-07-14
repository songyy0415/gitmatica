package me.niicide.lvc;

import java.nio.file.Path;
import me.niicide.lvc.task.LvcOperationHandle;
import me.niicide.lvc.task.LvcTaskEpochAssertions;
import me.niicide.lvc.task.LvcTaskRegistry;

final class LvcTaskRegistryIntegrationTest
{
    private static final String LOAD_OVERLAY = "LVC Load Overlay";

    private LvcTaskRegistryIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("overlay loading blocks foreground operations",
                LvcTaskRegistryIntegrationTest::overlayLoadingBlocksForegroundOperations);
        IntegrationTestSupport.run("foreground operations block overlay loading",
                LvcTaskRegistryIntegrationTest::foregroundOperationsBlockOverlayLoading);
        IntegrationTestSupport.run("world unload clears foreground and background ownership",
                LvcTaskRegistryIntegrationTest::worldUnloadClearsOperationOwnership);
        IntegrationTestSupport.run("world unload invalidates callbacks from the previous task epoch",
                LvcTaskEpochAssertions::assertWorldUnloadInvalidatesPreviousEpoch);
        IntegrationTestSupport.run("specialized LVC tasks participate in world unload fencing",
                LvcTaskEpochAssertions::assertSpecializedTasksAreWorldBound);
    }

    private static void overlayLoadingBlocksForegroundOperations()
    {
        Path repository = Path.of("build", "integration-test", "overlay-operation-lock");
        LvcOperationHandle overlay = LvcTaskRegistry.tryAcquireBackground(LOAD_OVERLAY, repository).orElseThrow();

        try
        {
            IntegrationTestSupport.assertTrue(LvcTaskRegistry.hasActiveOperation(),
                    "overlay loading should count as an active operation");
            IntegrationTestSupport.assertEquals("Load Overlay", LvcTaskRegistry.activeOperationName(),
                    "overlay loading should identify the blocking operation without the internal LVC prefix");
            IntegrationTestSupport.assertTrue(LvcTaskRegistry.tryAcquire("LVC Checkout", repository).isEmpty(),
                    "checkout must wait for overlay loading to finish");
        }
        finally
        {
            LvcTaskRegistry.release(overlay);
        }

        LvcOperationHandle checkout = LvcTaskRegistry.tryAcquire("LVC Checkout", repository).orElseThrow();
        IntegrationTestSupport.assertEquals("Checkout", LvcTaskRegistry.activeOperationName(),
                "foreground operation messages should omit the internal LVC prefix");
        LvcTaskRegistry.release(checkout);
    }

    private static void foregroundOperationsBlockOverlayLoading()
    {
        Path repository = Path.of("build", "integration-test", "foreground-operation-lock");
        LvcOperationHandle checkout = LvcTaskRegistry.tryAcquire("LVC Checkout", repository).orElseThrow();

        try
        {
            IntegrationTestSupport.assertTrue(LvcTaskRegistry.tryAcquireBackground(LOAD_OVERLAY, repository).isEmpty(),
                    "overlay loading must wait for checkout to finish");
        }
        finally
        {
            LvcTaskRegistry.release(checkout);
        }

        LvcOperationHandle overlay = LvcTaskRegistry.tryAcquireBackground(LOAD_OVERLAY, repository).orElseThrow();
        LvcTaskRegistry.release(overlay);
    }

    private static void worldUnloadClearsOperationOwnership()
    {
        Path repository = Path.of("build", "integration-test", "world-unload-operation-lock");
        LvcTaskRegistry.tryAcquire("LVC Checkout", repository).orElseThrow();

        LvcTaskRegistry.abortActiveOperationForWorldUnload();

        IntegrationTestSupport.assertTrue(!LvcTaskRegistry.hasActiveOperation(),
                "world unload should synchronously release foreground ownership");
        LvcTaskRegistry.tryAcquireBackground(LOAD_OVERLAY, repository).orElseThrow();

        LvcTaskRegistry.abortActiveOperationForWorldUnload();

        IntegrationTestSupport.assertTrue(!LvcTaskRegistry.hasActiveOperation(),
                "world unload should synchronously release background ownership");
        LvcOperationHandle checkout = LvcTaskRegistry.tryAcquire("LVC Checkout", repository).orElseThrow();
        LvcTaskRegistry.release(checkout);
    }
}
