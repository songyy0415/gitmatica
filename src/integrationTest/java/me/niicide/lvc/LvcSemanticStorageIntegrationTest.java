package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.areaSelectionFromBoxes;
import static me.niicide.lvc.LvcIntegrationFixtures.blockPosToList;
import static me.niicide.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.niicide.lvc.LvcIntegrationFixtures.boxJson;
import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.itemList;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleBlockEntityChunk;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import me.niicide.lvc.capture.LvcCaptureEngine;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcChunkCoordinate;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.storage.LvcCanonicalNbt;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcHashIndexCodec;
import me.niicide.lvc.storage.LvcRepository;
import me.niicide.lvc.storage.LvcSemanticRepository;
import me.niicide.lvc.storage.LvcSemanticObjectPruner;
import me.niicide.lvc.task.LvcRemoteSparseTargetPlanner;
import me.niicide.lvc.task.LvcSemanticRestoreEngine;
import me.niicide.lvc.util.LvcEntityNbt;
import me.niicide.lvc.git.LvcProjectGitOps;
import me.niicide.lvc.world.LvcWorldBackend;

final class LvcSemanticStorageIntegrationTest
{
    private LvcSemanticStorageIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic chunk encoding is deterministic and round trips", LvcSemanticStorageIntegrationTest::semanticChunkEncodingIsDeterministicAndRoundTrips);
        IntegrationTestSupport.run("semantic chunk storage writes raw payloads", LvcSemanticStorageIntegrationTest::semanticChunkStorageWritesRawPayloads);
        IntegrationTestSupport.run("semantic chunk rejects duplicate block entity records", LvcSemanticStorageIntegrationTest::semanticChunkRejectsDuplicateBlockEntityRecords);
        IntegrationTestSupport.run("semantic chunk decode rejects truncated payloads", LvcSemanticStorageIntegrationTest::semanticChunkDecodeRejectsTruncatedPayloads);
        IntegrationTestSupport.run("semantic chunk store writes content addressed objects once", LvcSemanticStorageIntegrationTest::semanticChunkStoreWritesContentAddressedObjectsOnce);
        IntegrationTestSupport.run("semantic hash index codec stores full and tracked hashes", LvcSemanticStorageIntegrationTest::semanticHashIndexCodecStoresFullAndTrackedHashes);
        IntegrationTestSupport.run("semantic manifest round trip", LvcSemanticStorageIntegrationTest::semanticManifestAndLocalStateRoundTrip);
        IntegrationTestSupport.run("semantic manifest allows overlapping regions as tracked union masks", LvcSemanticStorageIntegrationTest::semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks);
        IntegrationTestSupport.run("minecraft block state strings are canonical", LvcSemanticStorageIntegrationTest::minecraftBlockStateStringsAreCanonical);
        IntegrationTestSupport.run("canonical block entity nbt sorts keys and ignores position", LvcSemanticStorageIntegrationTest::canonicalBlockEntityNbtSortsKeysAndIgnoresPosition);
        IntegrationTestSupport.run("semantic entity nbt strips runtime entity ids", LvcSemanticStorageIntegrationTest::semanticEntityNbtStripsRuntimeEntityIds);
        IntegrationTestSupport.run("servux bulk entity nbt becomes project relative", LvcSemanticStorageIntegrationTest::servuxBulkEntityNbtBecomesProjectRelative);
        IntegrationTestSupport.run("tracked chunk hash includes block entity NBT", LvcSemanticStorageIntegrationTest::trackedChunkHashIncludesBlockEntityNbt);
        IntegrationTestSupport.run("tracked chunk hash normalizes stored air variants", LvcSemanticStorageIntegrationTest::trackedChunkHashNormalizesStoredAirVariants);
        IntegrationTestSupport.run("project service maps selection to semantic site", LvcSemanticStorageIntegrationTest::projectServiceMapsSelectionToSemanticSite);
        IntegrationTestSupport.run("project service updates regions from existing placement origin", LvcSemanticStorageIntegrationTest::projectServiceUpdatesRegionsFromExistingPlacementOrigin);
        IntegrationTestSupport.run("tracking overlay first load uses seeded project origin", LvcSemanticStorageIntegrationTest::trackingOverlayFirstLoadUsesSeededProjectOrigin);
        IntegrationTestSupport.run("project service creates empty browser project without commit", LvcSemanticStorageIntegrationTest::projectServiceCreatesEmptyBrowserProjectWithoutCommit);
        IntegrationTestSupport.run("project summary reports no origin when placement is not loaded", LvcSemanticStorageIntegrationTest::projectSummaryToleratesMissingLocalStateWithoutCreatingIt);
        IntegrationTestSupport.run("capture stores gaps as untracked positions", LvcSemanticStorageIntegrationTest::captureStoresGapsAsUntrackedPositions);
        IntegrationTestSupport.run("capture changes only the intersecting storage chunk hash", LvcSemanticStorageIntegrationTest::captureChangesOnlyTheIntersectingStorageChunkHash);
        IntegrationTestSupport.run("capture applies local site origin before reading world", LvcSemanticStorageIntegrationTest::captureAppliesLocalSiteOriginBeforeReadingWorld);
        IntegrationTestSupport.run("semantic scan hashes without writing objects", LvcSemanticStorageIntegrationTest::semanticScanHashesWithoutWritingObjects);
        IntegrationTestSupport.run("semantic scan detects block entity only changes", LvcSemanticStorageIntegrationTest::semanticScanDetectsBlockEntityOnlyChanges);
        IntegrationTestSupport.run("semantic scan ignores entity only changes", LvcSemanticStorageIntegrationTest::semanticScanIgnoresEntityOnlyChanges);
        IntegrationTestSupport.run("semantic restore modes handle block entity only changes by operation", LvcSemanticStorageIntegrationTest::semanticRestoreModesHandleBlockEntityOnlyChangesByOperation);
        IntegrationTestSupport.run("semantic scan compares tracked hashes not object references", LvcSemanticStorageIntegrationTest::semanticScanComparesTrackedHashesNotObjectReferences);
        IntegrationTestSupport.run("semantic scan preflight can ignore stale stored tracked hashes", LvcSemanticStorageIntegrationTest::semanticScanPreflightCanIgnoreStaleStoredTrackedHashes);
        IntegrationTestSupport.run("semantic scan reports unavailable chunks as unknown", LvcSemanticStorageIntegrationTest::semanticScanReportsUnavailableChunksAsUnknown);
        IntegrationTestSupport.run("semantic repository init commits manifest and objects", LvcSemanticStorageIntegrationTest::semanticRepositoryInitCommitsManifestAndObjects);
        IntegrationTestSupport.run("tracking overlay display name includes short head commit", LvcSemanticStorageIntegrationTest::trackingOverlayDisplayNameIncludesShortHeadCommit);
        IntegrationTestSupport.run("semantic tracking overlay cache is file backed", LvcSemanticStorageIntegrationTest::semanticTrackingOverlayCacheIsFileBacked);
        IntegrationTestSupport.run("corrupt semantic tracking overlay descriptor is recoverable", LvcSemanticStorageIntegrationTest::corruptSemanticTrackingOverlayDescriptorIsRecoverable);
        IntegrationTestSupport.run("semantic working tree schematic loads full hashes", LvcSemanticStorageIntegrationTest::semanticWorkingTreeSchematicLoadsFullHashes);
        IntegrationTestSupport.run("semantic sparse schematic uses structure void for skipped blocks", LvcSemanticStorageIntegrationTest::semanticSparseSchematicUsesStructureVoidForSkippedBlocks);
        IntegrationTestSupport.run("remote command sparse planner builds block state only schematic", LvcSemanticStorageIntegrationTest::remoteCommandSparsePlannerBuildsBlockStateOnlySchematic);
        IntegrationTestSupport.run("remote command sparse planner skips untracked gaps", LvcSemanticStorageIntegrationTest::remoteCommandSparsePlannerSkipsUntrackedGaps);
        IntegrationTestSupport.run("remote command sparse planner rejects unreadable blocks", LvcSemanticStorageIntegrationTest::remoteCommandSparsePlannerRejectsUnreadableBlocks);
        IntegrationTestSupport.run("semantic working tree schematic loads block entity NBT", LvcSemanticStorageIntegrationTest::semanticWorkingTreeSchematicLoadsBlockEntityNbt);
        IntegrationTestSupport.run("semantic working tree schematic promotes container components", LvcSemanticStorageIntegrationTest::semanticWorkingTreeSchematicPromotesContainerComponents);
        IntegrationTestSupport.run("semantic commit export uses selected commit", LvcSemanticStorageIntegrationTest::semanticCommitExportUsesSelectedCommit);
        IntegrationTestSupport.run("semantic repository no-op commit reports no changes", LvcSemanticStorageIntegrationTest::semanticRepositoryNoOpCommitReportsNoChanges);
        IntegrationTestSupport.run("semantic lossy commit empties previous block entities", LvcSemanticStorageIntegrationTest::semanticLossyCommitEmptiesPreviousBlockEntities);
        IntegrationTestSupport.run("semantic repository commit updates only changed full hash", LvcSemanticStorageIntegrationTest::semanticRepositoryCommitUpdatesOnlyChangedChunkReference);
        IntegrationTestSupport.run("semantic repository commit prunes replaced object from current head", LvcSemanticStorageIntegrationTest::semanticRepositoryCommitPrunesReplacedObjectFromCurrentHead);
        IntegrationTestSupport.run("semantic repository commit keeps shared live object candidate", LvcSemanticStorageIntegrationTest::semanticRepositoryCommitKeepsSharedLiveObjectCandidate);
        IntegrationTestSupport.run("semantic object pruner aborts before deleting when new object is missing", LvcSemanticStorageIntegrationTest::semanticObjectPrunerAbortsBeforeDeletingWhenNewObjectIsMissing);
        IntegrationTestSupport.run("semantic repository update areas changes regions and full hashes", LvcSemanticStorageIntegrationTest::semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs);
    }

    private static void semanticChunkEncodingIsDeterministicAndRoundTrips() throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        mask.set(1);
        mask.set(LvcChunk.DEFAULT_VOLUME - 1);
        byte[] entityNbt = entityPayload("minecraft:armor_stand", 0.5, 1.0, 0.5);

        LvcChunk chunk = new LvcChunk(
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                mask,
                List.of("minecraft:air", "minecraft:stone"),
                new int[] { 0, 1, 1 },
                List.of(new LvcChunk.BlockEntityRecord(1, new byte[] { 10, 1, 2, 3 })),
                List.of(new LvcChunk.EntityRecord(entityNbt))
        );

        byte[] first = LvcChunkCodec.encode(chunk);
        byte[] second = LvcChunkCodec.encode(chunk);
        IntegrationTestSupport.assertTrue(Arrays.equals(first, second), "same semantic chunk should encode to identical bytes");

        LvcChunk decoded = LvcChunkCodec.decode(first);
        IntegrationTestSupport.assertEquals(LvcChunk.DEFAULT_SIZE, decoded.sizeX(), "decoded size x");
        IntegrationTestSupport.assertEquals(3, decoded.trackedCount(), "decoded tracked count");
        IntegrationTestSupport.assertEquals(List.of("minecraft:air", "minecraft:stone"), decoded.palette(), "decoded palette");
        IntegrationTestSupport.assertEquals("minecraft:air", decoded.blockStateAtTrackedOrdinal(0), "tracked air must round-trip as tracked content");
        IntegrationTestSupport.assertEquals("minecraft:stone", decoded.blockStateAtTrackedOrdinal(2), "last tracked block state");
        IntegrationTestSupport.assertEquals(1, decoded.blockEntities().size(), "decoded block entity count");
        IntegrationTestSupport.assertTrue(Arrays.equals(new byte[] { 10, 1, 2, 3 }, decoded.blockEntities().get(0).canonicalNbt()), "decoded block entity bytes");
        IntegrationTestSupport.assertEquals(1, decoded.entities().size(), "decoded entity count");
        IntegrationTestSupport.assertTrue(Arrays.equals(entityNbt, decoded.entities().get(0).canonicalNbt()), "decoded entity bytes");

        LvcChunk trackedView = LvcChunkCodec.decode(LvcChunkCodec.encodeStorageBytes(LvcChunkCodec.encodeTrackedContent(chunk)));
        IntegrationTestSupport.assertEquals(1, trackedView.blockEntities().size(), "tracked view should include block entity NBT");
        IntegrationTestSupport.assertTrue(Arrays.equals(new byte[] { 10, 1, 2, 3 }, trackedView.blockEntities().get(0).canonicalNbt()), "tracked view block entity bytes");
        IntegrationTestSupport.assertEquals(0, trackedView.entities().size(), "tracked view should hide entity NBT");
    }

    private static void semanticChunkStorageWritesRawPayloads() throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0, LvcChunk.DEFAULT_VOLUME);
        String blockState = "minecraft:redstone_wire[east=side,north=side,power=15,south=side,west=side]";
        LvcChunk chunk = LvcChunk.fromTrackedBlockStates(mask, java.util.Collections.nCopies(LvcChunk.DEFAULT_VOLUME, blockState));

        byte[] canonical = LvcChunkCodec.encodeHashContent(chunk);
        byte[] stored = LvcChunkCodec.encode(chunk);
        LvcChunk decoded = LvcChunkCodec.decode(stored);

        IntegrationTestSupport.assertEquals(canonical.length + LvcChunkCodec.STORAGE_HEADER_LENGTH, stored.length, "raw chunk storage should only add storage magic");
        IntegrationTestSupport.assertEquals(blockState, decoded.blockStateAtTrackedOrdinal(LvcChunk.DEFAULT_VOLUME - 1), "raw chunk should decode repeated block state");
        IntegrationTestSupport.assertTrue(!LvcChunkStore.objectId(canonical).equals(LvcChunkStore.objectId(stored)), "object id should be based on canonical content, not storage wrapper bytes");
    }

    private static void semanticChunkRejectsDuplicateBlockEntityRecords()
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);

        try
        {
            new LvcChunk(
                    LvcChunk.DEFAULT_SIZE,
                    LvcChunk.DEFAULT_SIZE,
                    LvcChunk.DEFAULT_SIZE,
                    mask,
                    List.of("minecraft:stone"),
                    new int[] { 0 },
                    List.of(
                            new LvcChunk.BlockEntityRecord(0, new byte[] { 10, 0 }),
                            new LvcChunk.BlockEntityRecord(0, new byte[] { 10, 0 })
                    )
            );
            throw new AssertionError("duplicate block entity records should be rejected");
        }
        catch (IllegalArgumentException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("duplicate block entity"), "duplicate block entity error should explain the invalid record");
        }
    }

    private static void semanticChunkDecodeRejectsTruncatedPayloads() throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        LvcChunk chunk = new LvcChunk(
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                mask,
                List.of("minecraft:stone"),
                new int[] { 0 },
                List.of(new LvcChunk.BlockEntityRecord(0, new byte[] { 10, 0 }))
        );
        byte[] bytes = LvcChunkCodec.encode(chunk);

        try
        {
            LvcChunkCodec.decode(Arrays.copyOf(bytes, bytes.length - 1));
            throw new AssertionError("truncated semantic chunk should be rejected");
        }
        catch (java.io.IOException e)
        {
            // Expected: any decode failure is acceptable as long as the truncated object is not accepted.
        }
    }

    private static void semanticChunkStoreWritesContentAddressedObjectsOnce() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-store-");
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);

        LvcChunk chunk = LvcChunk.fromTrackedBlockStates(mask, List.of("minecraft:stone"));
        byte[] hashContentBytes = LvcChunkCodec.encodeHashContent(chunk);
        byte[] bytes = LvcChunkCodec.encodeStorageBytes(hashContentBytes);
        String expectedObjectId = LvcChunkStore.objectId(hashContentBytes);
        String objectId = LvcChunkStore.writeObjectIfMissing(repoDir, expectedObjectId, bytes);
        Path objectPath = LvcChunkStore.objectPath(repoDir, objectId);
        long firstModified = Files.getLastModifiedTime(objectPath).toMillis();

        String secondObjectId = LvcChunkStore.writeObjectIfMissing(repoDir, expectedObjectId, bytes);
        long secondModified = Files.getLastModifiedTime(objectPath).toMillis();

        IntegrationTestSupport.assertEquals(expectedObjectId, objectId, "chunk object id should come from canonical content bytes");
        IntegrationTestSupport.assertEquals(objectId, secondObjectId, "same content should produce the same object id");
        IntegrationTestSupport.assertTrue(Files.exists(objectPath), "object file should exist");
        IntegrationTestSupport.assertEquals(firstModified, secondModified, "existing object should not be rewritten");
        IntegrationTestSupport.assertTrue(Arrays.equals(bytes, LvcChunkStore.readObject(repoDir, objectId)), "object bytes should read back unchanged");
        IntegrationTestSupport.assertEquals("minecraft:stone", LvcChunkCodec.decode(LvcChunkStore.readObject(repoDir, objectId)).blockStateAtTrackedOrdinal(0), "stored raw object should decode");
        IntegrationTestSupport.assertTrue(objectPath.toString().contains("/objects/sha256/"), "object path should use sha256 fanout directory");
    }

    private static void semanticHashIndexCodecStoresFullAndTrackedHashes() throws Exception
    {
        String fullA = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String trackedA = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
        String fullB = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        String trackedB = "sha256:3333333333333333333333333333333333333333333333333333333333333333";
        Map<String, String> fullHashes = Map.of("0,0,0", fullA, "3,-2,1", fullB);
        Map<String, String> trackedHashes = Map.of("0,0,0", trackedA, "3,-2,1", trackedB);

        byte[] encoded = LvcHashIndexCodec.encode(fullHashes, trackedHashes);
        LvcHashIndexCodec.HashRefs decoded = LvcHashIndexCodec.decode(encoded);

        IntegrationTestSupport.assertEquals(fullHashes, decoded.fullHashes(), "binary hash index should round-trip full hashes");
        IntegrationTestSupport.assertEquals(trackedHashes, decoded.trackedHashes(), "binary hash index should round-trip tracked hashes");

        Path repoDir = Files.createTempDirectory("lvc-hash-index-");
        Path indexPath = repoDir.resolve(LvcHashIndexCodec.defaultIndexPath("main"));
        LvcHashIndexCodec.write(indexPath, fullHashes, trackedHashes);
        IntegrationTestSupport.assertTrue(Files.isRegularFile(indexPath), "hash index file should be written");
        IntegrationTestSupport.assertEquals(fullHashes, LvcHashIndexCodec.read(indexPath).fullHashes(), "hash index file should read full hashes");
    }

    private static void semanticManifestAndLocalStateRoundTrip() throws Exception
    {
        String objectId = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        LvcManifest.Site overworldMain = new LvcManifest.Site(
                "overworld_main",
                "Overworld Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("storage", "Storage", List.of(0, 0, 0), List.of(32, 16, 32))),
                Map.of("0,0,0", objectId)
        );
        LvcManifest.Site overworldRemote = new LvcManifest.Site(
                "overworld_remote",
                "Overworld Remote",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("dropoff", "Dropoff", List.of(0, 0, 0), List.of(16, 16, 16))),
                Map.of()
        );
        LvcManifest manifest = LvcManifest.create("Gold Farm", List.of(overworldMain, overworldRemote));
        String json = manifest.toJson();

        IntegrationTestSupport.assertTrue(json.contains("\"project_id\""), "manifest should use project_id JSON key");
        IntegrationTestSupport.assertTrue(!json.contains("\"content\""), "manifest should not serialize internal content settings");
        IntegrationTestSupport.assertTrue(!json.contains("\"chunk_size\""), "manifest should not expose internal chunk size");
        IntegrationTestSupport.assertTrue(!json.contains("\"hash_index_format\""), "manifest should not expose internal hash index format");
        IntegrationTestSupport.assertTrue(json.contains("\"hash_index\""), "manifest should reference external hash index");
        IntegrationTestSupport.assertTrue(!json.contains("\"full_hashes\""), "manifest should not serialize full hashes");
        IntegrationTestSupport.assertTrue(!json.contains("\"chunks\""), "manifest should not serialize old chunks key");
        IntegrationTestSupport.assertTrue(!json.contains("\"tracked_hashes\""), "manifest should not serialize tracked hashes");

        LvcManifest decodedManifest = LvcManifest.fromJson(json);
        IntegrationTestSupport.assertEquals("Gold Farm", decodedManifest.name(), "manifest name");
        IntegrationTestSupport.assertEquals(LvcManifest.Content.defaultContent(), decodedManifest.content(), "manifest should hydrate internal content defaults");
        IntegrationTestSupport.assertEquals(2, decodedManifest.sites().size(), "same dimension multi-site manifest should be valid");
        IntegrationTestSupport.assertEquals("indexes/overworld_main.lvcidx", decodedManifest.sites().get(0).hashIndex(), "manifest should round-trip hash index reference");
        IntegrationTestSupport.assertEquals(0, decodedManifest.sites().get(0).fullHashes().size(), "manifest JSON alone should not carry full hashes");
        IntegrationTestSupport.assertEquals(0, decodedManifest.sites().get(0).trackedHashes().size(), "manifest JSON alone should not carry tracked hashes");

        String userEditedContentJson = json.replaceFirst(
                "\"sites\"",
                "\"content\": {\"chunk_format\": \"user-edit\", \"hash_index_format\": \"user-edit\", \"hash\": \"md5\", \"chunk_size\": [99, 99, 99]},\n  \"sites\""
        );
        IntegrationTestSupport.assertTrue(LvcManifest.hasSerializedContent(userEditedContentJson), "old/user-edited content block should be detectable for diagnostics");
        LvcManifest userEditedContentManifest = LvcManifest.fromJson(userEditedContentJson);
        IntegrationTestSupport.assertEquals(LvcManifest.Content.defaultContent(), userEditedContentManifest.content(), "manifest JSON content block must not override internal content defaults");
        IntegrationTestSupport.assertTrue(!userEditedContentManifest.toJson().contains("\"content\""), "rewritten manifest should drop old content block");

    }

    private static void semanticManifestAllowsOverlappingRegionsAsTrackedUnionMasks() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-overlap-union-");
        LvcManifest manifest = LvcManifest.create("Overlap", List.of(new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(
                        new LvcManifest.Region("a", "A", List.of(0, 0, 0), List.of(2, 1, 1)),
                        new LvcManifest.Region("b", "B", List.of(1, 0, 0), List.of(2, 1, 1))
                ),
                Map.of()
        )));
        LvcManifest.Site site = manifest.site("main");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");

        LvcCaptureEngine.Result result = LvcCaptureEngine.captureSite(repoDir, site, placementAt(0, 0, 0), reader);
        LvcChunk chunk = readOnlyCapturedChunk(repoDir, result);

        IntegrationTestSupport.assertEquals(2, site.regions().size(), "overlapping region definitions should be preserved");
        IntegrationTestSupport.assertEquals(3, chunk.trackedCount(), "overlapping regions should track the union, not duplicate shared blocks");
        IntegrationTestSupport.assertEquals(3, reader.requestedPositions.size(), "overlap position should be read once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(0), "first region start should be tracked");
        IntegrationTestSupport.assertTrue(chunk.isTracked(1), "shared overlap should be tracked once");
        IntegrationTestSupport.assertTrue(chunk.isTracked(2), "second region end should be tracked");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(0), "first tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:dirt", chunk.blockStateAtTrackedOrdinal(1), "overlapped tracked block state");
        IntegrationTestSupport.assertEquals("minecraft:stone", chunk.blockStateAtTrackedOrdinal(2), "last tracked block state");
    }

    private static void minecraftBlockStateStringsAreCanonical()
    {
        bootstrapMinecraft();

        BlockState repeater = Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.DELAY, 3)
                .setValue(RepeaterBlock.FACING, Direction.NORTH)
                .setValue(RepeaterBlock.LOCKED, true);

        IntegrationTestSupport.assertEquals("minecraft:repeater[delay=3,facing=north,locked=true,powered=false]", LvcMinecraftWorldReader.blockStateString(repeater), "properties should be sorted by name");
        IntegrationTestSupport.assertEquals("minecraft:stone", LvcMinecraftWorldReader.blockStateString(Blocks.STONE.defaultBlockState()), "single-state blocks should omit property brackets");
        IntegrationTestSupport.assertEquals("minecraft:air", LvcMinecraftWorldReader.blockStateString(Blocks.CAVE_AIR.defaultBlockState()), "cave air should not dirty tracked content separately from air");
        IntegrationTestSupport.assertEquals("minecraft:air", LvcMinecraftWorldReader.blockStateString(Blocks.VOID_AIR.defaultBlockState()), "void air should not dirty tracked content separately from air");
    }

    private static void canonicalBlockEntityNbtSortsKeysAndIgnoresPosition() throws Exception
    {
        CompoundTag first = new CompoundTag();
        first.putInt("z", 999);
        first.putString("id", "minecraft:chest");
        first.putInt("x", 123);
        first.putInt("y", 64);
        first.put("Items", itemList(1, "minecraft:stone"));
        CompoundTag nestedFirst = new CompoundTag();
        nestedFirst.putString("b", "two");
        nestedFirst.putString("a", "one");
        first.put("Custom", nestedFirst);

        CompoundTag second = new CompoundTag();
        CompoundTag nestedSecond = new CompoundTag();
        nestedSecond.putString("a", "one");
        nestedSecond.putString("b", "two");
        second.put("Custom", nestedSecond);
        second.put("Items", itemList(1, "minecraft:stone"));
        second.putInt("y", -10);
        second.putInt("x", -20);
        second.putString("id", "minecraft:chest");
        second.putInt("z", -30);

        byte[] firstBytes = LvcCanonicalNbt.encodeBlockEntity(first);
        byte[] secondBytes = LvcCanonicalNbt.encodeBlockEntity(second);

        IntegrationTestSupport.assertTrue(Arrays.equals(firstBytes, secondBytes), "canonical block entity bytes should ignore absolute position and key insertion order");

        second.getListOrEmpty("Items").getCompoundOrEmpty(0).putString("id", "minecraft:dirt");
        IntegrationTestSupport.assertTrue(!Arrays.equals(firstBytes, LvcCanonicalNbt.encodeBlockEntity(second)), "semantic block entity content changes should affect bytes");
    }

    private static void semanticEntityNbtStripsRuntimeEntityIds() throws Exception
    {
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:armor_stand");
        passenger.putInt("LastEntityID", 99);
        NbtUtils.putVec3dCodec(passenger, new Vec3(0.5, 1.0, 0.5), "Pos");

        ListTag passengers = new ListTag();
        passengers.add(passenger);

        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:boat");
        entity.putInt("LastEntityID", 42);
        entity.putInt("entityId", 43);
        NbtUtils.putVec3dCodec(entity, new Vec3(0.5, 0.0, 0.5), "Pos");
        entity.put("Passengers", passengers);

        byte[] bytes = LvcCanonicalNbt.encodeUnnamed(entity);
        CompoundTag world = LvcEntityNbt.materializeForWorld(bytes, new LvcIntPosition(10, 64, 10));
        CompoundTag region = LvcEntityNbt.materializeForRegion(bytes, new LvcIntPosition(1, 2, 3));

        IntegrationTestSupport.assertTrue(!world.contains("LastEntityID"), "world entity restore must not preserve stale runtime entity id");
        IntegrationTestSupport.assertTrue(!world.contains("entityId"), "world entity restore must not preserve Servux runtime entity id");
        IntegrationTestSupport.assertTrue(!world.getListOrEmpty("Passengers").getCompoundOrEmpty(0).contains("LastEntityID"), "world passenger restore must not preserve stale runtime entity id");
        IntegrationTestSupport.assertTrue(!region.contains("LastEntityID"), "schematic entity materialization must not preserve stale runtime entity id");
        IntegrationTestSupport.assertTrue(!region.contains("entityId"), "schematic entity materialization must not preserve Servux runtime entity id");
        IntegrationTestSupport.assertTrue(!region.getListOrEmpty("Passengers").getCompoundOrEmpty(0).contains("LastEntityID"), "schematic passenger materialization must not preserve stale runtime entity id");
    }

    private static void servuxBulkEntityNbtBecomesProjectRelative() throws Exception
    {
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:armor_stand");
        passenger.putInt("LastEntityID", 99);
        passenger.putInt("entityId", 100);
        NbtUtils.putVec3dCodec(passenger, new Vec3(34.5, 70.0, 54.5), "Pos");

        ListTag passengers = new ListTag();
        passengers.add(passenger);

        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:boat");
        entity.putInt("LastEntityID", 42);
        entity.putInt("entityId", 43);
        NbtUtils.putVec3dCodec(entity, new Vec3(2.5, 4.0, 6.5), "Pos");
        entity.put("Passengers", passengers);

        byte[] bytes = LvcEntityNbt.captureServuxBulkProjectRelative(entity, new ChunkPos(2, 3), 64, new LvcIntPosition(30, 60, 45));
        CompoundTag stored = LvcCanonicalNbt.decodeUnnamedCompound(bytes);
        Vec3 rootPos = NbtUtils.getVec3dCodec(stored, "Pos");
        CompoundTag storedPassenger = stored.getListOrEmpty("Passengers").getCompoundOrEmpty(0);
        Vec3 passengerPos = NbtUtils.getVec3dCodec(storedPassenger, "Pos");

        IntegrationTestSupport.assertEquals(new Vec3(4.5, 8.0, 9.5), rootPos, "Servux chunk-relative root position should become project-relative");
        IntegrationTestSupport.assertEquals(new Vec3(4.5, 10.0, 9.5), passengerPos, "Servux passenger absolute position should become project-relative");
        IntegrationTestSupport.assertTrue(!stored.contains("LastEntityID"), "Servux root LastEntityID should be stripped");
        IntegrationTestSupport.assertTrue(!stored.contains("entityId"), "Servux root entityId should be stripped");
        IntegrationTestSupport.assertTrue(!storedPassenger.contains("LastEntityID"), "Servux passenger LastEntityID should be stripped");
        IntegrationTestSupport.assertTrue(!storedPassenger.contains("entityId"), "Servux passenger entityId should be stripped");
    }

    private static void trackedChunkHashIncludesBlockEntityNbt() throws Exception
    {
        CompoundTag firstBlockEntity = inventoryBlockEntity("minecraft:stone", "Tracked Name");
        CompoundTag secondBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        LvcChunk first = singleBlockEntityChunk(firstBlockEntity);
        LvcChunk second = singleBlockEntityChunk(secondBlockEntity);

        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeHashContent(first), LvcChunkCodec.encodeHashContent(second)), "canonical full content bytes should keep inventory payload");
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(first), LvcChunkCodec.encodeTrackedContent(second)), "tracked bytes should include inventory payload");

        CompoundTag timerBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        timerBlockEntity.putInt("CookTime", 42);
        timerBlockEntity.putInt("BurnTime", 12);
        LvcChunk timer = singleBlockEntityChunk(timerBlockEntity);
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(timer)), "tracked bytes should include canonical block entity NBT");

        CompoundTag renamedBlockEntity = inventoryBlockEntity("minecraft:dirt", "Renamed");
        LvcChunk renamed = singleBlockEntityChunk(renamedBlockEntity);
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(renamed)), "tracked bytes should include block entity NBT");
    }

    private static void trackedChunkHashNormalizesStoredAirVariants() throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        LvcChunk air = LvcChunk.fromTrackedBlockStates(mask, List.of("minecraft:air"));
        LvcChunk caveAir = LvcChunk.fromTrackedBlockStates(mask, List.of("minecraft:cave_air"));
        LvcChunk voidAir = LvcChunk.fromTrackedBlockStates(mask, List.of("minecraft:void_air"));
        byte[] airBytes = LvcChunkCodec.encodeTrackedContent(air);

        IntegrationTestSupport.assertTrue(Arrays.equals(airBytes, LvcChunkCodec.encodeTrackedContent(caveAir)), "tracked content should treat cave_air as air");
        IntegrationTestSupport.assertTrue(Arrays.equals(airBytes, LvcChunkCodec.encodeTrackedContent(voidAir)), "tracked content should treat void_air as air");
    }

    private static void projectServiceMapsSelectionToSemanticSite()
    {
        AreaSelection selection = areaSelectionFromBoxes(
                "Farm",
                List.of(
                        boxJson("Main Storage", new BlockPos(10, 64, 10), new BlockPos(11, 65, 11)),
                        boxJson("Main/Storage", new BlockPos(20, 70, 20), new BlockPos(20, 70, 20))
                )
        );

        LvcManifest.Site site = LvcProjectService.createMainSiteFromSelection("Farm", "minecraft:overworld", selection);
        LvcSitePlacement placement = LvcProjectService.createSitePlacement(selection.getEffectiveOrigin(), "minecraft:overworld");

        IntegrationTestSupport.assertEquals("main", site.id(), "MVP creates one active site");
        IntegrationTestSupport.assertEquals("minecraft:overworld", site.dimension(), "site dimension");
        IntegrationTestSupport.assertEquals(2, site.regions().size(), "selection boxes become explicit regions");
        IntegrationTestSupport.assertEquals("main_storage", site.regions().get(0).id(), "region id should be stable and safe");
        IntegrationTestSupport.assertEquals("main_storage_2", site.regions().get(1).id(), "duplicate region ids should be uniqued");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), site.regions().get(0).min(), "first region should be relative to placement origin");
        IntegrationTestSupport.assertEquals(List.of(2, 2, 2), site.regions().get(0).size(), "region size should be inclusive of both corners");
        IntegrationTestSupport.assertEquals(List.of(10, 64, 10), placement.origin(), "placement stores the world origin");
    }

    private static void projectServiceUpdatesRegionsFromExistingPlacementOrigin()
    {
        LvcManifest.Region existing = new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1));
        AreaSelection expandedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Line", new BlockPos(10, 64, 10), new BlockPos(26, 64, 10)))
        );

        List<LvcManifest.Region> expanded = LvcProjectService.createRegionsFromSelection(expandedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", expanded.get(0).id(), "same region name should preserve id while resizing");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), expanded.get(0).min(), "update areas should stay relative to existing placement origin");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.get(0).size(), "expanded region size");

        AreaSelection renamedSelection = areaSelectionFromBoxes(
                "Farm",
                List.of(boxJson("Renamed Line", new BlockPos(10, 64, 10), new BlockPos(10, 64, 10)))
        );
        List<LvcManifest.Region> renamed = LvcProjectService.createRegionsFromSelection(renamedSelection, new BlockPos(10, 64, 10), List.of(existing));

        IntegrationTestSupport.assertEquals("line", renamed.get(0).id(), "same bounds should preserve id while renaming");
        IntegrationTestSupport.assertEquals("Renamed Line", renamed.get(0).name(), "region display name should update");
    }

    private static void trackingOverlayFirstLoadUsesSeededProjectOrigin() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-overlay-seeded-origin-");
        AreaSelection selection = areaSelectionFromBoxes(
                "Farm",
                List.of(
                        boxJson("Storage", new BlockPos(20, 70, 20), new BlockPos(20, 70, 20)),
                        boxJson("Barn", new BlockPos(10, 64, 10), new BlockPos(11, 64, 11))
                )
        );
        LvcManifest.Site site = LvcProjectService.createMainSiteFromSelection("Farm", "minecraft:overworld", selection);
        LvcSitePlacement placement = LvcProjectService.createSitePlacement(selection.getEffectiveOrigin(), "minecraft:overworld");

        LvcTrackingOverlayService.seedTrackingOverlayOrigin(repoDir, placement);
        LvcSitePlacement resolved = LvcTrackingOverlayService.resolveSitePlacementForTrackingOverlay(repoDir, site);

        IntegrationTestSupport.assertEquals(List.of(10, 64, 10), resolved.origin(), "first overlay load should use selection-derived project origin");
    }

    private static void projectServiceCreatesEmptyBrowserProjectWithoutCommit() throws Exception
    {
        Path gameDir = Files.createTempDirectory("lvc-empty-project-game-");
        BlockPos origin = new BlockPos(12, 65, -4);
        LvcProjectService.EmptyProjectResult created = LvcProjectService.createEmptyProject(gameDir, "Manual Project", "minecraft:overworld");
        Path repoDir = created.repositoryDirectory();

        IntegrationTestSupport.assertEquals("Manual Project", created.projectName(), "manual project display name");
        IntegrationTestSupport.assertTrue(Files.isDirectory(repoDir.resolve(".git")), "manual project should initialize Git repository");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(repoDir.resolve(LvcSemanticRepository.MANIFEST)), "manual project should write manifest");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "manual project should not write local.json");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("README.md")), "manual project should not generate README");
        IntegrationTestSupport.assertEquals(null, LvcRepository.resolveHead(repoDir), "manual project should start without an initial commit");
        IntegrationTestSupport.assertEquals(0, LvcProjectService.listCommits(repoDir).size(), "manual project history should start empty");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertEquals(Constants.R_HEADS + LvcProjectService.DEFAULT_BRANCH, git.getRepository().getFullBranch(), "manual project should initialize the default branch");
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repoDir);

        IntegrationTestSupport.assertEquals(0, manifest.site("main").regions().size(), "manual project should start with no regions");
        IntegrationTestSupport.assertEquals(0, manifest.site("main").fullHashes().size(), "manual project should start with no full hashes");
        IntegrationTestSupport.assertEquals(0, manifest.site("main").trackedHashes().size(), "manual project should start with no tracked hashes");

        try
        {
            LvcProjectService.createSemanticRegion(repoDir, "First Area", BlockPos.ZERO, new BlockPos(1, 1, 1));
            throw new AssertionError("manual project region edit should require a loaded placement");
        }
        catch (LvcUserActionException e)
        {
            IntegrationTestSupport.assertEquals(LvcUserActionException.Reason.MISSING_PLACEMENT, e.reason(), "manual project region edit should require a loaded placement");
        }

        LvcManifest withRegion = manifest.withSite(
                "main",
                manifest.site("main").withRegions(List.of(new LvcManifest.Region("first_area", "First Area", List.of(0, 0, 0), List.of(2, 2, 2))))
        );
        LvcSemanticRepository.writeVersionedProjectFiles(repoDir, withRegion);
        LvcSemanticRepository.CommitResult firstCommit = LvcSemanticRepository.commitSite(
                repoDir,
                withRegion,
                "main",
                placementAt(origin.getX(), origin.getY(), origin.getZ()),
                new FakeWorldReader("minecraft:stone"),
                player("ManualProject"),
                "first version"
        );

        IntegrationTestSupport.assertNotNull(firstCommit.commit(), "manual project first region save should create first commit");
        IntegrationTestSupport.assertEquals(firstCommit.commit().getId(), LvcRepository.resolveHead(repoDir), "manual project first commit should become HEAD");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").regions().size(), "first commit should keep the new region");
        IntegrationTestSupport.assertEquals(1, firstCommit.manifest().site("main").fullHashes().size(), "first commit should capture tracked content");
        IntegrationTestSupport.assertEquals(firstCommit.manifest().site("main").fullHashes().keySet(), firstCommit.manifest().site("main").trackedHashes().keySet(), "first commit should track hash per full hash");
    }

    private static void projectSummaryToleratesMissingLocalStateWithoutCreatingIt() throws Exception
    {
        Path gameDir = Files.createTempDirectory("lvc-summary-missing-local-game-");
        Path repoDir = LvcProjectService.createEmptyProject(gameDir, "Summary Project", "minecraft:overworld").repositoryDirectory();

        LvcProjectService.ProjectSummary summary = LvcProjectService.projectSummary(new LvcProjectService.Project("Summary Project", repoDir));

        IntegrationTestSupport.assertEquals("Summary Project", summary.name(), "summary should still read manifest name");
        IntegrationTestSupport.assertEquals(null, summary.origin(), "summary should report unknown origin when placement is not loaded");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "summary should not generate local.json");
    }

    private static void captureStoresGapsAsUntrackedPositions() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-capture-gaps-");
        LvcManifest.Site site = validatedSingleSite(List.of(
                new LvcManifest.Region("left", "Left", List.of(0, 0, 0), List.of(1, 1, 1)),
                new LvcManifest.Region("right", "Right", List.of(2, 0, 0), List.of(1, 1, 1))
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
        LvcManifest.Site site = validatedSingleSite(List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(17, 1, 1))));
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
        LvcManifest.Site site = validatedSingleSite(List.of(new LvcManifest.Region("area", "Area", List.of(0, 0, 0), List.of(16, 1, 16))));
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

    private static void semanticScanDetectsBlockEntityOnlyChanges() throws Exception
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

        IntegrationTestSupport.assertEquals(1, result.changedChunks(), "block entity only changes should scan dirty");
        IntegrationTestSupport.assertTrue(!committed.trackedHashes().equals(scan.trackedHashes()), "block entity only changes should update tracked hashes");
        IntegrationTestSupport.assertTrue(reader.blockEntityReadCount() > 0, "scan should read block entity NBT for tracked hashes");

        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:dirt", "Renamed"));
        reader.resetBlockEntityReadCount();
        LvcCaptureEngine.Result renamedScan = LvcCaptureEngine.scanSite(site, placement, reader);
        LvcProjectService.SemanticScanResult renamedResult = LvcProjectService.SemanticScanResult.compare("main", committed.trackedHashes(), renamedScan);
        IntegrationTestSupport.assertEquals(1, renamedResult.changedChunks(), "block entity NBT-only changes should scan dirty");
        IntegrationTestSupport.assertTrue(!committed.trackedHashes().equals(renamedScan.trackedHashes()), "block entity NBT-only changes should update tracked hashes");
        IntegrationTestSupport.assertTrue(reader.blockEntityReadCount() > 0, "scan should read renamed block entity NBT");

        reader.resetBlockEntityReadCount();
        LvcCaptureEngine.Result fullCapture = LvcCaptureEngine.captureSite(repoDir, site, placement, reader);
        IntegrationTestSupport.assertTrue(!committed.fullHashes().equals(fullCapture.fullHashes()), "full hashes should still store changed block entity payload");
        IntegrationTestSupport.assertTrue(!committed.trackedHashes().equals(fullCapture.trackedHashes()), "full capture should update tracked hash for block entity only changes");
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

    private static void semanticRepositoryInitCommitsManifestAndObjects() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-init-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult result = LvcSemanticRepository.initProject(repoDir, "Semantic Init", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticInit"));

        IntegrationTestSupport.assertNotNull(result.commit(), "semantic init should create a commit");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(LvcSemanticRepository.MANIFEST), "\"format\": \"lvc-manifest-v1\"");
        IntegrationTestSupport.assertFileContains(repoDir.resolve(LvcSemanticRepository.MANIFEST), "\"hash_index\"");
        String manifestText = Files.readString(repoDir.resolve(LvcSemanticRepository.MANIFEST));
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"content\""), "lvc.json should not expose internal content settings");
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"full_hashes\""), "lvc.json should not contain full hash map");
        IntegrationTestSupport.assertTrue(!manifestText.contains("\"tracked_hashes\""), "lvc.json should not contain tracked hash map");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("local.json")), "semantic init should not write local.json");
        IntegrationTestSupport.assertTrue(!Files.exists(repoDir.resolve("README.md")), "semantic init should not generate README");

        String objectId = result.manifest().site("main").fullHashes().get("0,0,0");
        Path hashIndexPath = repoDir.resolve(result.manifest().site("main").hashIndex());
        IntegrationTestSupport.assertTrue(Files.isRegularFile(hashIndexPath), "semantic init should write hash index");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").fullHashes(), LvcHashIndexCodec.read(hashIndexPath).fullHashes(), "hash index should store full hashes");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").trackedHashes(), LvcHashIndexCodec.read(hashIndexPath).trackedHashes(), "hash index should store tracked hashes");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, objectId)), "semantic init should write captured chunk object");
        IntegrationTestSupport.assertEquals(result.manifest().site("main").fullHashes().keySet(), result.manifest().site("main").trackedHashes().keySet(), "semantic init should write tracked hash per full hash");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            ObjectId headId = repository.resolve(Constants.HEAD);
            IntegrationTestSupport.assertNotNull(headId, "semantic repo HEAD");
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "semantic repo should be clean after init");

            try (RevWalk revWalk = new RevWalk(repository))
            {
                RevCommit commit = revWalk.parseCommit(headId);
                Set<String> files = committedFiles(repository, commit);

                IntegrationTestSupport.assertTrue(files.contains(LvcSemanticRepository.MANIFEST), "semantic commit should include lvc.json");
                IntegrationTestSupport.assertTrue(!files.contains("README.md"), "semantic commit should not include generated README");
                IntegrationTestSupport.assertTrue(files.stream().anyMatch(path -> path.endsWith(LvcHashIndexCodec.EXTENSION)), "semantic commit should include hash index");
                IntegrationTestSupport.assertTrue(files.stream().anyMatch(path -> path.endsWith(LvcChunkStore.EXTENSION)), "semantic commit should include chunk object");
                IntegrationTestSupport.assertTrue(!files.contains("local.json"), "semantic commit must not include local.json");

                String rawCommit = new String(repository.open(headId).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nlvc-version 1\n"), "semantic commit should include LVC metadata");
                IntegrationTestSupport.assertTrue(rawCommit.contains("\nx-created-by lvc\n"), "semantic commit should include created-by metadata");
            }
        }
    }

    private static void trackingOverlayDisplayNameIncludesShortHeadCommit() throws Exception
    {
        Path emptyDir = Files.createTempDirectory("lvc-overlay-name-empty-");
        IntegrationTestSupport.assertEquals("Display Project", LvcProjectService.trackingOverlayDisplayName(emptyDir, "Display Project"), "overlay name without HEAD");

        Path repoDir = Files.createTempDirectory("lvc-overlay-name-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Display Project", singleLineSite(1), placementAt(0, 0, 0), reader, player("OverlayName"));
        String shortHead = init.commit().getId().getName().substring(0, 8);
        String expectedName = "Display Project @ " + shortHead;

        IntegrationTestSupport.assertEquals(expectedName, LvcProjectService.trackingOverlayDisplayName(repoDir, "Display Project"), "overlay name with HEAD");
    }

    private static void semanticTrackingOverlayCacheIsFileBacked() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-file-overlay-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "File Overlay", singleLineSite(1), placementAt(0, 0, 0), reader, player("FileOverlay"));

        Path expectedDirectory = repoDir.toAbsolutePath().normalize().resolve(".git").resolve("lvc-cache").normalize();
        Path actualFile = LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "File Overlay");
        String actualFileName = actualFile.getFileName().toString();

        IntegrationTestSupport.assertEquals(expectedDirectory, actualFile.getParent(), "semantic tracking overlay cache should use the local Git cache path");
        IntegrationTestSupport.assertTrue(actualFileName.startsWith("tracking-overlay-"), "semantic tracking overlay cache should use a generated tracking overlay file name");
        IntegrationTestSupport.assertTrue(actualFileName.endsWith(".litematic"), "semantic tracking overlay cache should be a litematic file");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(actualFile), "semantic tracking overlay cache file should exist");

        Path secondFile = LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "File Overlay");
        IntegrationTestSupport.assertTrue(!actualFile.equals(secondFile), "semantic tracking overlay cache writes should not overwrite the previous file path");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(secondFile), "second semantic tracking overlay cache file should exist");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "semantic overlay cache under .git should not dirty the project");
        }
    }

    private static void corruptSemanticTrackingOverlayDescriptorIsRecoverable() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-corrupt-overlay-descriptor-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Corrupt Overlay", singleLineSite(1), placementAt(0, 0, 0), reader, player("CorruptOverlay"));
        Path descriptor = repoDir.toAbsolutePath().normalize().resolve(".git").resolve("lvc-cache").resolve("tracking-overlay.json");

        LvcProjectService.writeSemanticTrackingCacheFile(repoDir, init.manifest(), "main", placementAt(0, 0, 0), "Corrupt Overlay");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "{", StandardCharsets.UTF_8);

        IntegrationTestSupport.assertTrue(!LvcTrackingOverlayService.isSemanticTrackingCacheCurrent(repoDir),
                "corrupt semantic tracking overlay descriptor should be ignored so the overlay can rebuild");
    }

    private static void semanticRepositoryNoOpCommitReportsNoChanges() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-noop-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Noop", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticNoop"));
        ObjectId headBefore = LvcRepository.resolveHead(repoDir);

        LvcSemanticRepository.CommitResult noOp = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main", placementAt(0, 0, 0), reader, player("SemanticNoop"), "same content");

        IntegrationTestSupport.assertEquals(null, noOp.commit(), "same semantic content should not create a commit");
        IntegrationTestSupport.assertEquals(headBefore, LvcRepository.resolveHead(repoDir), "no-op semantic commit should not move HEAD");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").fullHashes(), noOp.manifest().site("main").fullHashes(), "no-op semantic capture should keep full hashes");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").trackedHashes(), noOp.manifest().site("main").trackedHashes(), "no-op semantic capture should keep tracked hashes");
    }

    private static void semanticLossyCommitEmptiesPreviousBlockEntities() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-lossy-empty-be-");
        String furnace = LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState());
        FakeWorldReader servuxLikeReader = new FakeWorldReader(furnace);
        servuxLikeReader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:diamond", "Captured Inventory"));
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Lossy Empty BE",
                singleLineSite(1), placementAt(0, 0, 0), servuxLikeReader, player("LossyEmptyBe"));
        ObjectId headBefore = LvcRepository.resolveHead(repoDir);
        int initialBlockEntityRecords = LvcSemanticRepository.countBlockEntityRecords(repoDir, init.manifest().site("main"));

        FakeWorldReader commandLikeReader = new FakeWorldReader(furnace);
        LvcSemanticRepository.CommitResult lossy = LvcSemanticRepository.commitSite(repoDir, init.manifest(),
                "main", placementAt(0, 0, 0), commandLikeReader, player("LossyEmptyBe"), "capture without servux");

        IntegrationTestSupport.assertNotNull(lossy.commit(), "lossy no-Servux capture should commit inventory removal");
        IntegrationTestSupport.assertTrue(!headBefore.equals(lossy.commit().getId()), "lossy inventory-empty commit should move HEAD");
        IntegrationTestSupport.assertEquals(1, initialBlockEntityRecords, "initial Servux-like capture should store the inventory payload");
        IntegrationTestSupport.assertEquals(0, LvcSemanticRepository.countBlockEntityRecords(repoDir, lossy.manifest().site("main")),
                "command-like capture should store the container as empty/no block entity payload");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").trackedHashes().equals(lossy.manifest().site("main").trackedHashes()),
                "inventory removal should be a tracked semantic diff");
        LvcChunk emptied = readOnlyCapturedChunk(repoDir, new LvcCaptureEngine.Result(lossy.manifest().site("main").fullHashes()));
        IntegrationTestSupport.assertEquals(0, emptied.blockEntities().size(), "lossy captured chunk should not retain stale block entity NBT");
    }

    private static void semanticWorkingTreeSchematicLoadsFullHashes() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-schematic-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Schematic", singleLineSite(2), placementAt(0, 0, 0), reader, player("SemanticSchematic"));

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildWorkingTreeSchematic(repoDir, init.manifest(), "main", placementAt(0, 0, 0));
        fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container = schematic.getSubRegionContainer("Line");

        IntegrationTestSupport.assertNotNull(container, "semantic schematic should create a region container");
        IntegrationTestSupport.assertEquals(1, schematic.getSubRegionCount(), "semantic schematic region count");
        IntegrationTestSupport.assertEquals(List.of(0, 0, 0), blockPosToList(schematic.getSubRegionPosition("Line")), "semantic schematic region offset");
        IntegrationTestSupport.assertEquals(Blocks.STONE.defaultBlockState(), container.get(0, 0, 0), "first semantic block state");
        IntegrationTestSupport.assertEquals(Blocks.DIRT.defaultBlockState(), container.get(1, 0, 0), "second semantic block state");
    }

    private static void semanticSparseSchematicUsesStructureVoidForSkippedBlocks() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-sparse-schematic-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Sparse Schematic",
                singleLineSite(2), placementAt(0, 0, 0), reader, player("SemanticSparseSchematic"));

        LvcSemanticSchematicBuilder.BuildSession session = LvcSemanticSchematicBuilder.beginSchematicBuild(
                init.manifest(),
                "main",
                placementAt(0, 0, 0),
                objectId -> LvcChunkStore.readObject(repoDir, objectId),
                null,
                (block, parsedState) -> block.projectPos().x() == 1
        );

        while (!session.isComplete())
        {
            session.processNextChunk();
        }

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = session.result();
        fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container = schematic.getSubRegionContainer("Line");

        IntegrationTestSupport.assertNotNull(container, "semantic sparse schematic should create a region container");
        IntegrationTestSupport.assertEquals(Blocks.STRUCTURE_VOID.defaultBlockState(), container.get(0, 0, 0), "skipped semantic block should become structure void");
        IntegrationTestSupport.assertEquals(Blocks.DIRT.defaultBlockState(), container.get(1, 0, 0), "included semantic block should keep target state");
        IntegrationTestSupport.assertEquals(1, session.includedBlocks(), "sparse schematic included block count");
        IntegrationTestSupport.assertEquals(1, session.structureVoidBlocks(), "sparse schematic structure void block count");
    }

    private static void remoteCommandSparsePlannerBuildsBlockStateOnlySchematic() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-command-sparse-schematic-");
        String furnace = LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState());
        FakeWorldReader targetReader = new FakeWorldReader("minecraft:stone");
        targetReader.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        targetReader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:diamond", "Stored Inventory"));
        targetReader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Command Sparse Schematic",
                singleLineSite(3), placementAt(0, 0, 0), targetReader, player("CommandSparseSchematic"));

        FakeWorldReader liveReader = new FakeWorldReader("minecraft:stone");
        liveReader.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        liveReader.setBlock(new LvcIntPosition(2, 0, 0), furnace);
        LvcRemoteSparseTargetPlanner planner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.COMMANDS, liveReader, init.manifest().site("main"));
        LvcSemanticSchematicBuilder.BuildSession session = LvcSemanticSchematicBuilder.beginSchematicBuild(
                init.manifest(),
                "main",
                placementAt(0, 0, 0),
                objectId -> LvcChunkStore.readObject(repoDir, objectId),
                null,
                planner::include
        );

        finishSchematicBuild(session);

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = session.result();
        fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer container = schematic.getSubRegionContainer("Line");

        IntegrationTestSupport.assertNotNull(container, "command sparse schematic should create a region container");
        IntegrationTestSupport.assertEquals(Blocks.STRUCTURE_VOID.defaultBlockState(), container.get(0, 0, 0), "command sparse planner should skip block entity only targets");
        IntegrationTestSupport.assertEquals(Blocks.DIRT.defaultBlockState(), container.get(1, 0, 0), "command sparse planner should include changed block states");
        IntegrationTestSupport.assertEquals(Blocks.STONE.defaultBlockState(), container.get(2, 0, 0), "command sparse planner should include furnace block state overwrite");
        IntegrationTestSupport.assertEquals(2, session.includedBlocks(), "command sparse included block count");
        IntegrationTestSupport.assertEquals(1, session.structureVoidBlocks(), "command sparse skipped block count");
        IntegrationTestSupport.assertEquals(3, planner.scannedBlocks(), "command sparse scanned blocks");
        IntegrationTestSupport.assertEquals(2, planner.stateMismatches(), "command sparse state mismatches");
        IntegrationTestSupport.assertEquals(0, planner.blockEntityMismatches(), "command sparse should not restore block entity only changes");
        IntegrationTestSupport.assertEquals(1, planner.ignoredBlockEntityTargets(), "command sparse should count ignored block entity targets");
        IntegrationTestSupport.assertEquals(0, liveReader.blockEntityReadCount(), "command sparse should not read live block entity NBT");
        IntegrationTestSupport.assertEquals(List.of(new BlockPos(2, 0, 0)), planner.furnaceXpCleanupCandidates(), "command sparse furnace cleanup candidates");
        IntegrationTestSupport.assertEquals(List.of(
                new LvcRemoteSparseTargetPlanner.CommandMutation(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState()),
                new LvcRemoteSparseTargetPlanner.CommandMutation(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState())
        ), planner.commandMutations(), "command sparse should queue exact changed block mutations");
        IntegrationTestSupport.assertTrue(planner.affectedRegionIds().contains("line"), "command sparse should mark changed regions");
    }

    private static void remoteCommandSparsePlannerSkipsUntrackedGaps() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-command-sparse-gaps-");
        LvcManifest.Site site = validatedSingleSite(List.of(
                new LvcManifest.Region("left", "Left", List.of(0, 0, 0), List.of(1, 1, 1)),
                new LvcManifest.Region("right", "Right", List.of(2, 0, 0), List.of(1, 1, 1))
        ));
        FakeWorldReader targetReader = new FakeWorldReader("minecraft:stone");
        targetReader.setBlock(new LvcIntPosition(2, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Command Sparse Gaps",
                site, placementAt(0, 0, 0), targetReader, player("CommandSparseGaps"));

        FakeWorldReader liveReader = new FakeWorldReader("minecraft:stone");
        LvcRemoteSparseTargetPlanner planner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.COMMANDS, liveReader, init.manifest().site("main"));
        LvcSemanticSchematicBuilder.BuildSession session = LvcSemanticSchematicBuilder.beginSchematicBuild(
                init.manifest(),
                "main",
                placementAt(0, 0, 0),
                objectId -> LvcChunkStore.readObject(repoDir, objectId),
                null,
                planner::include
        );

        finishSchematicBuild(session);

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = session.result();
        fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer left = schematic.getSubRegionContainer("Left");
        fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer right = schematic.getSubRegionContainer("Right");

        IntegrationTestSupport.assertNotNull(left, "command sparse gap test should create left region");
        IntegrationTestSupport.assertNotNull(right, "command sparse gap test should create right region");
        IntegrationTestSupport.assertEquals(Blocks.STRUCTURE_VOID.defaultBlockState(), left.get(0, 0, 0), "unchanged tracked left region should be skipped");
        IntegrationTestSupport.assertEquals(Blocks.DIRT.defaultBlockState(), right.get(0, 0, 0), "changed right region should be included");
        IntegrationTestSupport.assertEquals(1, session.includedBlocks(), "untracked gap sparse included count");
        IntegrationTestSupport.assertEquals(1, session.structureVoidBlocks(), "untracked gap sparse skipped tracked count");
        IntegrationTestSupport.assertEquals(Set.of("right"), planner.affectedRegionIds(), "only the changed region should get entity cleanup");
    }

    private static void remoteCommandSparsePlannerRejectsUnreadableBlocks() throws Exception
    {
        FakeWorldReader liveReader = new FakeWorldReader("minecraft:stone");
        liveReader.setUnavailable(new LvcIntPosition(0, 0, 0));
        LvcRemoteSparseTargetPlanner planner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.COMMANDS, liveReader, singleLineSite(1));
        LvcSemanticSchematicBuilder.TargetBlock block = new LvcSemanticSchematicBuilder.TargetBlock(
                new LvcChunkCoordinate(0, 0, 0),
                0,
                new LvcIntPosition(0, 0, 0),
                new LvcIntPosition(0, 0, 0),
                BlockPos.ZERO,
                "minecraft:stone",
                null
        );

        try
        {
            planner.include(block, Blocks.STONE.defaultBlockState());
            throw new AssertionError("unreadable command sparse target should abort");
        }
        catch (java.io.IOException e)
        {
            IntegrationTestSupport.assertTrue(e.getMessage().contains("cannot read tracked block"), "unreadable sparse error should explain the blocked read");
        }

        IntegrationTestSupport.assertEquals(0, planner.scannedBlocks(), "unreadable target should abort before scanning");
    }

    private static void semanticWorkingTreeSchematicLoadsBlockEntityNbt() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-schematic-nbt-");
        FakeWorldReader reader = new FakeWorldReader(LvcMinecraftWorldReader.blockStateString(Blocks.CHEST.defaultBlockState()));
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        blockEntity.putString("custom_name", "LVC Test Chest");
        blockEntity.putInt("x", 123);
        blockEntity.putInt("y", 64);
        blockEntity.putInt("z", -9);
        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), blockEntity);
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic NBT Schematic", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticSchematicNbt"));

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildWorkingTreeSchematic(repoDir, init.manifest(), "main", placementAt(0, 0, 0));
        Map<BlockPos, CompoundTag> blockEntities = schematic.getBlockEntityMapForRegion("Line");
        CompoundTag restored = blockEntities.get(new BlockPos(0, 0, 0));

        IntegrationTestSupport.assertNotNull(restored, "semantic schematic should restore block entity NBT");
        IntegrationTestSupport.assertEquals("minecraft:chest", restored.getStringOr("id", ""), "semantic schematic block entity id");
        IntegrationTestSupport.assertEquals("LVC Test Chest", restored.getStringOr("custom_name", ""), "semantic schematic block entity custom data");
        IntegrationTestSupport.assertEquals(0, restored.getIntOr("x", -1), "semantic schematic block entity local x");
        IntegrationTestSupport.assertEquals(0, restored.getIntOr("y", -1), "semantic schematic block entity local y");
        IntegrationTestSupport.assertEquals(0, restored.getIntOr("z", -1), "semantic schematic block entity local z");
    }

    private static void semanticWorkingTreeSchematicPromotesContainerComponents() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-schematic-components-");
        FakeWorldReader reader = new FakeWorldReader(LvcMinecraftWorldReader.blockStateString(Blocks.CHEST.defaultBlockState()));
        CompoundTag blockEntity = new CompoundTag();
        CompoundTag components = new CompoundTag();
        ListTag container = new ListTag();
        CompoundTag slot = new CompoundTag();
        CompoundTag item = new CompoundTag();

        item.putString("id", "minecraft:purple_shulker_box");
        item.putInt("count", 5);
        slot.putInt("slot", 4);
        slot.put("item", item);
        container.add(slot);
        components.put("minecraft:container", container);

        blockEntity.putString("id", "minecraft:chest");
        blockEntity.put("components", components);
        reader.setBlockEntity(new LvcIntPosition(0, 0, 0), blockEntity);
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Component Inventory", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticComponentInventory"));

        fi.dy.masa.litematica.schematic.LitematicaSchematic schematic = LvcSemanticSchematicBuilder.buildWorkingTreeSchematic(repoDir, init.manifest(), "main", placementAt(0, 0, 0));
        CompoundTag restored = schematic.getBlockEntityMapForRegion("Line").get(new BlockPos(0, 0, 0));

        IntegrationTestSupport.assertNotNull(restored, "semantic schematic should restore component-backed block entity NBT");
        ListTag items = restored.getListOrEmpty("Items");
        CompoundTag restoredItem = items.getCompoundOrEmpty(0);

        IntegrationTestSupport.assertEquals(1, items.size(), "component-backed container should become Litematica-readable Items");
        IntegrationTestSupport.assertEquals((byte) 4, restoredItem.getByteOr("Slot", (byte) -1), "promoted item slot");
        IntegrationTestSupport.assertEquals("minecraft:purple_shulker_box", restoredItem.getStringOr("id", ""), "promoted item id");
        IntegrationTestSupport.assertEquals(5, restoredItem.getIntOr("count", -1), "promoted item count");
    }

    private static void semanticCommitExportUsesSelectedCommit() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-semantic-export-");
        Path outputDir = Files.createTempDirectory("lvc-semantic-export-out-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "ExportProject", singleLineSite(2), placementAt(0, 0, 0), reader, player("SemanticExport"));

        reader.setBlock(new LvcIntPosition(1, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main", placementAt(0, 0, 0), reader, player("SemanticExport"), "change export block");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic export content should create a commit");

        fi.dy.masa.litematica.schematic.LitematicaSchematic initialSchematic = LvcProjectService.buildSemanticCommitSchematic(repoDir, init.commit().getName());
        fi.dy.masa.litematica.schematic.LitematicaSchematic updatedSchematic = LvcProjectService.buildSemanticCommitSchematic(repoDir, update.commit().getName());

        IntegrationTestSupport.assertEquals(Blocks.STONE.defaultBlockState(), initialSchematic.getSubRegionContainer("Line").get(1, 0, 0), "initial exported commit should keep stone");
        IntegrationTestSupport.assertEquals(Blocks.DIRT.defaultBlockState(), updatedSchematic.getSubRegionContainer("Line").get(1, 0, 0), "updated exported commit should keep dirt");

        LvcProjectService.ExportResult first = LvcProjectService.exportCommitToLitematic(repoDir, init.commit().getName(), outputDir);
        LvcProjectService.ExportResult duplicate = LvcProjectService.exportCommitToLitematic(repoDir, init.commit().getName(), outputDir);
        String shortId = init.commit().getName().substring(0, 8);

        IntegrationTestSupport.assertEquals("ExportProject-" + shortId + ".litematic", first.fileName(), "first export filename");
        IntegrationTestSupport.assertEquals("ExportProject-" + shortId + ".litematic", duplicate.fileName(), "repeat export filename");
        IntegrationTestSupport.assertEquals(first.file(), duplicate.file(), "repeat export should overwrite the deterministic file");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(first.file()), "first exported litematic should exist");
        IntegrationTestSupport.assertTrue(Files.isRegularFile(duplicate.file()), "duplicate exported litematic should exist");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "export outside repo should not dirty the project");
        }
    }

    private static void semanticRepositoryCommitUpdatesOnlyChangedChunkReference() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-update-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Update", singleLineSite(17), placementAt(0, 0, 0), reader, player("SemanticUpdate"));

        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main", placementAt(0, 0, 0), reader, player("SemanticUpdate"), "change second chunk");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").fullHashes().get("0,0,0"), update.manifest().site("main").fullHashes().get("0,0,0"), "unchanged full hash should be reused");
        IntegrationTestSupport.assertEquals(init.manifest().site("main").trackedHashes().get("0,0,0"), update.manifest().site("main").trackedHashes().get("0,0,0"), "unchanged tracked hash should be reused");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").fullHashes().get("1,0,0").equals(update.manifest().site("main").fullHashes().get("1,0,0")), "changed full hash should update");
        IntegrationTestSupport.assertTrue(!init.manifest().site("main").trackedHashes().get("1,0,0").equals(update.manifest().site("main").trackedHashes().get("1,0,0")), "changed tracked hash should update");
        IntegrationTestSupport.assertEquals(update.manifest().site("main").fullHashes(), LvcSemanticRepository.readManifest(repoDir).site("main").fullHashes(), "updated manifest should be written to disk");
        IntegrationTestSupport.assertEquals(update.manifest().site("main").trackedHashes(), LvcSemanticRepository.readManifest(repoDir).site("main").trackedHashes(), "updated tracked hashes should be written to disk");
        IntegrationTestSupport.assertEquals(update.commit().getId(), LvcRepository.resolveHead(repoDir), "semantic update commit should move HEAD");
    }

    private static void semanticRepositoryCommitPrunesReplacedObjectFromCurrentHead() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Prune", singleLineSite(17), placementAt(0, 0, 0), reader, player("SemanticPrune"));
        String oldObject = init.manifest().site("main").fullHashes().get("1,0,0");

        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main",
                placementAt(0, 0, 0), reader, player("SemanticPrune"), "change second chunk");
        String newObject = update.manifest().site("main").fullHashes().get("1,0,0");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertTrue(!oldObject.equals(newObject), "changed chunk should get a new full object");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, oldObject)), "old replaced object should be pruned from current working tree");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, newObject)), "new replacement object should remain in current working tree");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            String oldObjectPath = LvcChunkStore.objectRepositoryPath(oldObject);
            IntegrationTestSupport.assertTrue(LvcProjectGitOps.readCommitFile(repository, init.commit(), oldObjectPath) != null, "previous commit should still contain pruned object");
            IntegrationTestSupport.assertEquals(null, LvcProjectGitOps.readCommitFile(repository, update.commit(), oldObjectPath), "current commit should not carry pruned object");
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "pruning should be staged in the semantic commit");
        }
    }

    private static void semanticRepositoryCommitKeepsSharedLiveObjectCandidate() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-shared-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Prune Shared", singleLineSite(32), placementAt(0, 0, 0), reader, player("SemanticPruneShared"));
        String sharedObject = init.manifest().site("main").fullHashes().get("0,0,0");

        IntegrationTestSupport.assertEquals(sharedObject, init.manifest().site("main").fullHashes().get("1,0,0"), "identical storage chunks should share one object");

        reader.setBlock(new LvcIntPosition(0, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult update = LvcSemanticRepository.commitSite(repoDir, init.manifest(), "main",
                placementAt(0, 0, 0), reader, player("SemanticPruneShared"), "change first shared chunk");

        IntegrationTestSupport.assertNotNull(update.commit(), "changed semantic content should create a commit");
        IntegrationTestSupport.assertTrue(!sharedObject.equals(update.manifest().site("main").fullHashes().get("0,0,0")), "changed chunk should stop using shared object");
        IntegrationTestSupport.assertEquals(sharedObject, update.manifest().site("main").fullHashes().get("1,0,0"), "unchanged chunk should keep shared object live");
        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, sharedObject)), "shared object candidate should not be pruned while still live");

        try (Git git = Git.open(repoDir.toFile()))
        {
            IntegrationTestSupport.assertTrue(git.status().call().isClean(), "shared-object prune skip should leave repo clean");
        }
    }

    private static void semanticObjectPrunerAbortsBeforeDeletingWhenNewObjectIsMissing() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-prune-missing-");
        String oldObject = objectId("old live object");
        String missingNewObject = objectId("missing new object");
        LvcChunkStore.writeObjectIfMissing(repoDir, oldObject, "old".getBytes(StandardCharsets.UTF_8));
        LvcManifest previous = manifestWithObject("Prune Missing", oldObject);
        LvcManifest resulting = manifestWithObject("Prune Missing", missingNewObject);

        try
        {
            LvcSemanticObjectPruner.pruneChangedObjects(repoDir, previous, resulting, "main");
            throw new AssertionError("pruner should reject missing newly referenced objects");
        }
        catch (java.io.IOException expected)
        {
            IntegrationTestSupport.assertTrue(expected.getMessage().contains("Missing LVC object"), "missing object error should explain prune safety failure");
        }

        IntegrationTestSupport.assertTrue(Files.exists(LvcChunkStore.objectPath(repoDir, oldObject)), "old candidate must not be deleted after validation failure");
    }

    private static void semanticRepositoryUpdateAreasChangesRegionsAndChunkRefs() throws Exception
    {
        Path repoDir = Files.createTempDirectory("lvc-semantic-areas-");
        FakeWorldReader reader = new FakeWorldReader("minecraft:stone");
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Semantic Areas", singleLineSite(1), placementAt(0, 0, 0), reader, player("SemanticAreas"));
        ObjectId initHead = LvcRepository.resolveHead(repoDir);

        List<LvcManifest.Region> expandedRegions = List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(17, 1, 1)));
        reader.setBlock(new LvcIntPosition(16, 0, 0), "minecraft:dirt");
        LvcSemanticRepository.CommitResult expanded = LvcSemanticRepository.updateSiteAreas(repoDir, init.manifest(), "main", placementAt(0, 0, 0), expandedRegions, reader, player("SemanticAreas"), "expand area");

        IntegrationTestSupport.assertNotNull(expanded.commit(), "expanded area should create a commit");
        IntegrationTestSupport.assertTrue(!initHead.equals(expanded.commit().getId()), "expanded area should move HEAD");
        IntegrationTestSupport.assertEquals(List.of(17, 1, 1), expanded.manifest().site("main").regions().get(0).size(), "expanded region should be versioned");
        IntegrationTestSupport.assertEquals(2, expanded.manifest().site("main").fullHashes().size(), "17-block area should reference two LVC chunks");
        IntegrationTestSupport.assertEquals(expanded.manifest().site("main").fullHashes().keySet(), expanded.manifest().site("main").trackedHashes().keySet(), "expanded area should track hashes for all refs");
        String removedAreaObject = expanded.manifest().site("main").fullHashes().get("1,0,0");
        IntegrationTestSupport.assertTrue(!removedAreaObject.equals(expanded.manifest().site("main").fullHashes().get("0,0,0")), "removed chunk object should be unique for prune assertion");

        List<LvcManifest.Region> shrunkRegions = List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1)));
        LvcSemanticRepository.CommitResult shrunk = LvcSemanticRepository.updateSiteAreas(repoDir, expanded.manifest(), "main", placementAt(0, 0, 0), shrunkRegions, reader, player("SemanticAreas"), "shrink area");

        IntegrationTestSupport.assertNotNull(shrunk.commit(), "shrunk area should create a commit");
        IntegrationTestSupport.assertEquals(List.of(1, 1, 1), shrunk.manifest().site("main").regions().get(0).size(), "shrunk region should be versioned");
        IntegrationTestSupport.assertEquals(1, shrunk.manifest().site("main").fullHashes().size(), "full hashes with no tracked positions should leave the manifest");
        IntegrationTestSupport.assertEquals(shrunk.manifest().site("main").fullHashes().keySet(), shrunk.manifest().site("main").trackedHashes().keySet(), "shrunk area should keep hashes aligned to refs");
        IntegrationTestSupport.assertEquals(shrunk.commit().getId(), LvcRepository.resolveHead(repoDir), "semantic area update commit should move HEAD");
        IntegrationTestSupport.assertTrue(!Files.exists(LvcChunkStore.objectPath(repoDir, removedAreaObject)), "removed area chunk object should be pruned from current working tree");

        try (Git git = Git.open(repoDir.toFile()))
        {
            Repository repository = git.getRepository();
            String removedObjectPath = LvcChunkStore.objectRepositoryPath(removedAreaObject);
            IntegrationTestSupport.assertTrue(LvcProjectGitOps.readCommitFile(repository, expanded.commit(), removedObjectPath) != null, "expanded commit should still contain removed area object");
            IntegrationTestSupport.assertEquals(null, LvcProjectGitOps.readCommitFile(repository, shrunk.commit(), removedObjectPath), "shrunk commit should not carry removed area object");
        }
    }

    private static LvcManifest.Site validatedSingleSite(List<LvcManifest.Region> regions)
    {
        LvcManifest manifest = LvcManifest.create("Capture", List.of(new LvcManifest.Site("main", "Main", "minecraft:overworld", regions, Map.of())));
        return manifest.sites().get(0);
    }

    private static void finishSchematicBuild(LvcSemanticSchematicBuilder.BuildSession session) throws Exception
    {
        while (!session.isComplete())
        {
            session.processNextChunk();
        }
    }

    private static LvcManifest manifestWithObject(String name, String objectId)
    {
        return LvcManifest.create(name, List.of(new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("line", "Line", List.of(0, 0, 0), List.of(1, 1, 1))),
                Map.of("0,0,0", objectId)
        )));
    }

    private static LvcChunk readOnlyCapturedChunk(Path repoDir, LvcCaptureEngine.Result result) throws Exception
    {
        IntegrationTestSupport.assertEquals(1, result.fullHashes().size(), "expected exactly one captured chunk");
        String objectId = result.fullHashes().values().iterator().next();
        return LvcChunkCodec.decode(LvcChunkStore.readObject(repoDir, objectId));
    }

    private static byte[] entityPayload(String id, double x, double y, double z) throws Exception
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        NbtUtils.putVec3dCodec(tag, new Vec3(x, y, z), "Pos");
        return LvcCanonicalNbt.encodeUnnamed(tag);
    }

    private static Set<String> committedFiles(Repository repository, RevCommit commit) throws Exception
    {
        try (TreeWalk treeWalk = new TreeWalk(repository))
        {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            Set<String> files = new HashSet<>();

            while (treeWalk.next())
            {
                files.add(treeWalk.getPathString());
            }

            return files.stream().collect(Collectors.toSet());
        }
    }
}
