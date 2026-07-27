package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.niicide.lvc.LvcSemanticTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import me.niicide.lvc.capture.LvcCaptureEngine;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.task.LvcSemanticRestoreEngine;

final class LvcSemanticCaptureScanIntegrationTest
{
    private LvcSemanticCaptureScanIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("capture stores gaps as untracked positions", LvcSemanticCaptureScanIntegrationTest::captureStoresGapsAsUntrackedPositions);
        IntegrationTestSupport.run("capture changes only the intersecting storage chunk hash", LvcSemanticCaptureScanIntegrationTest::captureChangesOnlyTheIntersectingStorageChunkHash);
        IntegrationTestSupport.run("capture applies local site origin before reading world", LvcSemanticCaptureScanIntegrationTest::captureAppliesLocalSiteOriginBeforeReadingWorld);
        IntegrationTestSupport.run("semantic scan hashes without writing objects", LvcSemanticCaptureScanIntegrationTest::semanticScanHashesWithoutWritingObjects);
        IntegrationTestSupport.run("semantic scan detects block entity inventory changes", LvcSemanticCaptureScanIntegrationTest::semanticScanDetectsBlockEntityInventoryChanges);
        IntegrationTestSupport.run("semantic scan ignores entity only changes", LvcSemanticCaptureScanIntegrationTest::semanticScanIgnoresEntityOnlyChanges);
        IntegrationTestSupport.run("semantic restore modes handle block entity only changes by operation", LvcSemanticCaptureScanIntegrationTest::semanticRestoreModesHandleBlockEntityOnlyChangesByOperation);
        IntegrationTestSupport.run("semantic scan compares tracked hashes not object references", LvcSemanticCaptureScanIntegrationTest::semanticScanComparesTrackedHashesNotObjectReferences);
        IntegrationTestSupport.run("semantic scan preflight can ignore stale stored tracked hashes", LvcSemanticCaptureScanIntegrationTest::semanticScanPreflightCanIgnoreStaleStoredTrackedHashes);
        IntegrationTestSupport.run("semantic scan reports unavailable chunks as unknown", LvcSemanticCaptureScanIntegrationTest::semanticScanReportsUnavailableChunksAsUnknown);
    }

    private static void captureStoresGapsAsUntrackedPositions() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-capture-gaps-");
        LvcManifest.Site site = validatedSingleSite(List.of(
                new LvcManifest.Region("Left", List.of(0, 0, 0), List.of(1, 1, 1)),
                new LvcManifest.Region("Right", List.of(2, 0, 0), List.of(1, 1, 1))
        ));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:diamond_block");

        LvcCaptureEngine.Result result = LvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        LvcChunk chunk = readOnlyCapturedChunk(repoDir, result);

        IntegrationTestSupport.assertEquals(2, chunk.trackedCount(), "only user region positions should be tracked");
        IntegrationTestSupport.assertTrue(chunk.isTracked(0), "left region position should be tracked");
        IntegrationTestSupport.assertTrue(!chunk.isTracked(1), "gap position must be untracked, not tracked air or real world block");
        IntegrationTestSupport.assertTrue(chunk.isTracked(2), "right region position should be tracked");
        IntegrationTestSupport.assertEquals(List.of("minecraft:stone"), chunk.palette(), "gap block should not enter the chunk palette");
    }

    private static void captureChangesOnlyTheIntersectingStorageChunkHash() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-capture-change-");
        LvcManifest.Site site = validatedSingleSite(List.of(
                new LvcManifest.Region("Line", List.of(0, 0, 0), List.of(17, 1, 1))));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");

        LvcCaptureEngine.Result first = LvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcCaptureEngine.Result second = LvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);

        IntegrationTestSupport.assertEquals(2, first.fullHashes().size(), "17-block line should span two LVC chunks");
        IntegrationTestSupport.assertEquals(first.fullHashes().keySet(), first.trackedHashes().keySet(), "tracked hashes should align to full hashes");
        IntegrationTestSupport.assertEquals(first.fullHashes().get("0,0,0"), second.fullHashes().get("0,0,0"), "unchanged storage chunk hash should be reused");
        IntegrationTestSupport.assertEquals(first.trackedHashes().get("0,0,0"), second.trackedHashes().get("0,0,0"), "unchanged tracked chunk hash should be reused");
        IntegrationTestSupport.assertTrue(!first.fullHashes().get("1,0,0").equals(second.fullHashes().get("1,0,0")), "changed storage chunk hash should differ");
        IntegrationTestSupport.assertTrue(!first.trackedHashes().get("1,0,0").equals(second.trackedHashes().get("1,0,0")), "changed tracked chunk hash should differ");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, first.fullHashes().get("0,0,0"))), "unchanged object should exist in store");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, second.fullHashes().get("1,0,0"))), "changed object should exist in store");
    }

    private static void captureAppliesLocalSiteOriginBeforeReadingWorld() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-capture-origin-");
        LvcManifest.Site site = validatedSingleSite(List.of(
                new LvcManifest.Region("Area", List.of(0, 0, 0), List.of(16, 1, 16))));
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");

        LvcCaptureEngine.captureSite(repoDir, site, placementAt(5, 64, 5), reader);

        IntegrationTestSupport.assertTrue(reader.requestedPositions.contains(new LvcIntPosition(5, 64, 5)), "capture should read at local site origin");
        IntegrationTestSupport.assertTrue(reader.requestedPositions.contains(new LvcIntPosition(20, 64, 20)), "project-relative LVC chunk can cross Minecraft chunk boundaries after origin offset");
    }

    private static void semanticScanHashesWithoutWritingObjects() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-scan-");
        LvcManifest.Site site = singleLineSite(1);
        LvcSitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcCaptureEngine.Result committed = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setBlock(new LvcIntPosition(8, 0, 0), "minecraft:dirt");
        LvcCaptureEngine.Result outsideChangeScan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult clean = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), outsideChangeScan);

        IntegrationTestSupport.assertTrue(clean.clean(), "outside tracked region changes should scan as clean");
        IntegrationTestSupport.assertEquals(1, clean.unchangedChunks(), "clean scan should count the tracked chunk as unchanged");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcCaptureEngine.Result dirtyScan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult dirty = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), dirtyScan);
        String dirtyTrackedHash = dirtyScan.trackedHashes().get("0,0,0");

        IntegrationTestSupport.assertEquals(1, dirty.changedChunks(), "tracked block changes should scan as changed");
        IntegrationTestSupport.assertEquals(1, dirty.dirtyChunks(), "dirty chunk count");
        IntegrationTestSupport.assertTrue(dirtyScan.fullHashes().isEmpty(), "scan should not compute full hashes");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, dirtyTrackedHash)), "scan must not write newly hashed chunk objects");
    }

    private static void semanticScanDetectsBlockEntityInventoryChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-scan-inventory-");
        LvcManifest.Site site = singleLineSite(1);
        LvcSitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader(LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState()));
        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:stone", "Tracked Name"));
        LvcCaptureEngine.Result committed = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:dirt", "Tracked Name"));
        reader.resetBlockEntityReadCount();
        LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult result = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), scan);

        IntegrationTestSupport.assertEquals(1, result.changedChunks(), "block entity inventory changes should scan dirty");
        IntegrationTestSupport.assertTrue(!committed.trackedHashes().equals(scan.trackedHashes()), "block entity inventory changes should update tracked hashes");
        IntegrationTestSupport.assertTrue(reader.blockEntityReadCount() > 0, "scan should read block entity NBT for tracked hashes");

        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:stone", "Renamed"));
        reader.resetBlockEntityReadCount();
        LvcCaptureEngine.Result renamedScan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult renamedResult = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), renamedScan);
        IntegrationTestSupport.assertTrue(renamedResult.clean(), "non-inventory block entity NBT-only changes should scan clean");
        IntegrationTestSupport.assertEquals(committed.trackedHashes(), renamedScan.trackedHashes(), "non-inventory block entity NBT-only changes should keep tracked hashes");
        IntegrationTestSupport.assertTrue(reader.blockEntityReadCount() > 0, "scan should read renamed block entity NBT");

        CompoundTag timerBlockEntity = inventoryBlockEntity("minecraft:stone", "Tracked Name");
        timerBlockEntity.putInt("CookTime", 42);
        timerBlockEntity.putInt("BurnTime", 12);
        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), timerBlockEntity);
        LvcCaptureEngine.Result timerScan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult timerResult = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), timerScan);
        IntegrationTestSupport.assertTrue(timerResult.clean(), "timer-only block entity changes should scan clean");
        IntegrationTestSupport.assertEquals(committed.trackedHashes(), timerScan.trackedHashes(), "timer-only block entity changes should keep tracked hashes");

        reader.resetBlockEntityReadCount();
        LvcCaptureEngine.Result fullCapture = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);
        IntegrationTestSupport.assertTrue(!committed.fullHashes().equals(fullCapture.fullHashes()), "full hashes should still store changed block entity payload");
        IntegrationTestSupport.assertEquals(committed.trackedHashes(), fullCapture.trackedHashes(), "full capture should keep tracked hash clean for non-inventory block entity changes");
        IntegrationTestSupport.assertTrue(reader.blockEntityReadCount() > 0, "full capture should still read block entity NBT");
    }

    private static void semanticScanIgnoresEntityOnlyChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-scan-entities-");
        LvcManifest.Site site = singleLineSite(1);
        LvcSitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcChunkCoordinate coordinate = new LvcChunkCoordinate(0, 0, 0);

        reader.setEntities(coordinate, List.of(new LvcChunk.EntityRecord(entityPayload("minecraft:armor_stand", 0.5, 0.0, 0.5))));
        LvcCaptureEngine.Result committed = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setEntities(coordinate, List.of(new LvcChunk.EntityRecord(entityPayload("minecraft:item_frame", 0.5, 0.0, 0.5))));
        reader.resetEntityReadCount();
        LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult result = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), scan);

        IntegrationTestSupport.assertTrue(result.clean(), "entity only changes should scan clean");
        IntegrationTestSupport.assertEquals(committed.trackedHashes(), scan.trackedHashes(), "entity only changes should keep tracked hashes");
        IntegrationTestSupport.assertEquals(0, reader.entityReadCount(), "scan should not read entity NBT when only tracked hashes are needed");

        LvcCaptureEngine.Result fullCapture = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);
        IntegrationTestSupport.assertTrue(!committed.fullHashes().equals(fullCapture.fullHashes()), "full hashes should still store changed entity payload");
        IntegrationTestSupport.assertEquals(committed.trackedHashes(), fullCapture.trackedHashes(), "full capture should still keep tracked hash clean for entity only changes");
        IntegrationTestSupport.assertTrue(reader.entityReadCount() > 0, "full capture should still read entity NBT");
    }

    private static void semanticRestoreModesHandleBlockEntityOnlyChangesByOperation()
    {
        LvcSemanticRestoreEngine.Options discard = LvcSemanticRestoreEngine.Options.discard("head");
        LvcSemanticRestoreEngine.Options checkout = LvcSemanticRestoreEngine.Options.checkout("target");
        LvcSemanticRestoreEngine.Options clear = LvcSemanticRestoreEngine.Options.clear();

        IntegrationTestSupport.assertTrue(discard.restoreBlockEntityOnlyChanges(), "discard should restore block entity only drift because tracked hashes include inventories");
        IntegrationTestSupport.assertTrue(checkout.restoreBlockEntityOnlyChanges(), "checkout should still restore target block entity payloads");
        IntegrationTestSupport.assertTrue(clear.targetMode() == LvcSemanticRestoreEngine.TargetMode.CLEAR, "clear should use the air-target restore mode");
        IntegrationTestSupport.assertTrue(!clear.restoreStoredEntities(), "clear should not respawn stored entities");
    }

    private static void semanticScanComparesTrackedHashesNotObjectReferences()
    {
        String oldFullObject = objectId("old full object");
        String newFullObject = objectId("new full object");
        String trackedHash = objectId("same tracked content");

        LvcCaptureEngine.Result scan = new LvcCaptureEngine.Result(
                Map.of("0,0,0", newFullObject),
                Map.of("0,0,0", trackedHash),
                Set.of()
        );
        LvcProjectService.SemanticScanResult result = LvcProjectService.SemanticScanResult.compare("main", Map.of("0,0,0", trackedHash), scan);

        IntegrationTestSupport.assertTrue(!oldFullObject.equals(newFullObject), "test must use different full object ids");
        IntegrationTestSupport.assertTrue(result.clean(), "changed full object refs should scan clean when tracked hash is unchanged");
        IntegrationTestSupport.assertEquals(1, result.unchangedChunks(), "tracked hash match should count as unchanged");
    }

    private static void semanticScanPreflightCanIgnoreStaleStoredTrackedHashes() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-stale-tracked-");
        LvcManifest.Site site = singleLineSite(1);
        LvcSitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcCaptureEngine.Result committed = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);
        LvcManifest.Site staleManifestSite = site.withHashRefs(
                committed.fullHashes(),
                Map.of("0,0,0", objectId("stale tracked hash"))
        );
        LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(staleManifestSite, placement, reader);
        LvcProjectService.SemanticScanResult staleComparison = LvcProjectService.SemanticScanResult.compare(
                "main",
                staleManifestSite.trackedHashesForComparison(),
                scan
        );
        Map<String, String> recomputedTrackedHashes = LvcSemanticRepository.computeTrackedHashesFromFullObjects(repoDir, staleManifestSite);
        LvcProjectService.SemanticScanResult canonicalComparison = LvcProjectService.SemanticScanResult.compare(
                "main",
                recomputedTrackedHashes,
                scan
        );

        IntegrationTestSupport.assertEquals(committed.trackedHashes(), recomputedTrackedHashes, "tracked hashes should be derived from full objects");
        IntegrationTestSupport.assertEquals(1, staleComparison.changedChunks(), "stale stored tracked hash would produce a false dirty preflight");
        IntegrationTestSupport.assertTrue(canonicalComparison.clean(), "canonical full-object-derived tracked hash should keep clean preflight clean");
    }

    private static void semanticScanReportsUnavailableChunksAsUnknown() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-scan-unknown-");
        LvcManifest.Site site = singleLineSite(17);
        LvcSitePlacement placement = placementAt(0, 0, 0);
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcCaptureEngine.Result committed = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);

        reader.setUnavailable(new LvcIntPosition(16, 0, 0));
        LvcCaptureEngine.Result scan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult result = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), scan);

        IntegrationTestSupport.assertEquals(1, result.unknownChunks(), "unavailable tracked data should be reported as unknown");
        IntegrationTestSupport.assertEquals(1, result.unchangedChunks(), "available chunk should still compare clean");
        IntegrationTestSupport.assertEquals(0, result.removedChunks(), "unknown chunks must not be treated as removed");
        IntegrationTestSupport.assertTrue(!result.clean(), "unknown scan result is not clean");
    }
}
