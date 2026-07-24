package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.niicide.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.niicide.lvc.LvcIntegrationFixtures.itemList;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.singleBlockEntityChunk;
import static me.niicide.lvc.LvcSemanticTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.storage.LvcCanonicalNbt;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcHashIndexCodec;
import me.niicide.lvc.util.LvcEntityNbt;

final class LvcSemanticChunkStorageIntegrationTest
{
    private LvcSemanticChunkStorageIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic chunk encoding is deterministic and round trips", LvcSemanticChunkStorageIntegrationTest::semanticChunkEncodingIsDeterministicAndRoundTrips);
        IntegrationTestSupport.run("semantic chunk storage writes raw payloads", LvcSemanticChunkStorageIntegrationTest::semanticChunkStorageWritesRawPayloads);
        IntegrationTestSupport.run("semantic chunk rejects duplicate block entity records", LvcSemanticChunkStorageIntegrationTest::semanticChunkRejectsDuplicateBlockEntityRecords);
        IntegrationTestSupport.run("semantic chunk decode rejects truncated payloads", LvcSemanticChunkStorageIntegrationTest::semanticChunkDecodeRejectsTruncatedPayloads);
        IntegrationTestSupport.run("semantic chunk store writes content addressed objects once", LvcSemanticChunkStorageIntegrationTest::semanticChunkStoreWritesContentAddressedObjectsOnce);
        IntegrationTestSupport.run("semantic hash index codec stores full and tracked hashes", LvcSemanticChunkStorageIntegrationTest::semanticHashIndexCodecStoresFullAndTrackedHashes);
        IntegrationTestSupport.run("minecraft block state strings are canonical", LvcSemanticChunkStorageIntegrationTest::minecraftBlockStateStringsAreCanonical);
        IntegrationTestSupport.run("canonical block entity nbt sorts keys and ignores position", LvcSemanticChunkStorageIntegrationTest::canonicalBlockEntityNbtSortsKeysAndIgnoresPosition);
        IntegrationTestSupport.run("semantic entity nbt strips runtime entity ids", LvcSemanticChunkStorageIntegrationTest::semanticEntityNbtStripsRuntimeEntityIds);
        IntegrationTestSupport.run("servux bulk entity nbt becomes project relative", LvcSemanticChunkStorageIntegrationTest::servuxBulkEntityNbtBecomesProjectRelative);
        IntegrationTestSupport.run("tracked chunk hash includes verifier-visible block entity inventory", LvcSemanticChunkStorageIntegrationTest::trackedChunkHashIncludesVerifierVisibleBlockEntityInventory);
        IntegrationTestSupport.run("tracked chunk hash normalizes stored air variants", LvcSemanticChunkStorageIntegrationTest::trackedChunkHashNormalizesStoredAirVariants);
    }

    private static void semanticChunkEncodingIsDeterministicAndRoundTrips() throws Exception
    {
        BitSet mask = new BitSet(LvcChunk.DEFAULT_VOLUME);
        mask.set(0);
        mask.set(1);
        mask.set(LvcChunk.DEFAULT_VOLUME - 1);
        byte[] blockEntityNbt = LvcCanonicalNbt.encodeBlockEntity(inventoryBlockEntity("minecraft:stone", "Round Trip"));
        byte[] entityNbt = entityPayload("minecraft:armor_stand", 0.5, 1.0, 0.5);

        LvcChunk chunk = new LvcChunk(
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                LvcChunk.DEFAULT_SIZE,
                mask,
                List.of("minecraft:air", "minecraft:stone"),
                new int[] { 0, 1, 1 },
                List.of(new LvcChunk.BlockEntityRecord(1, blockEntityNbt)),
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
        IntegrationTestSupport.assertTrue(Arrays.equals(blockEntityNbt, decoded.blockEntities().get(0).canonicalNbt()), "decoded block entity bytes");
        IntegrationTestSupport.assertEquals(1, decoded.entities().size(), "decoded entity count");
        IntegrationTestSupport.assertTrue(Arrays.equals(entityNbt, decoded.entities().get(0).canonicalNbt()), "decoded entity bytes");

        LvcChunk trackedView = LvcChunkCodec.decode(LvcChunkCodec.encodeStorageBytes(LvcChunkCodec.encodeTrackedContent(chunk)));
        IntegrationTestSupport.assertEquals(1, trackedView.blockEntities().size(), "tracked view should include projected inventory block entity NBT");
        CompoundTag trackedBlockEntity = LvcCanonicalNbt.decodeUnnamedCompound(trackedView.blockEntities().get(0).canonicalNbt());
        CompoundTag trackedItem = trackedBlockEntity.getListOrEmpty("Items").getCompoundOrEmpty(0);
        IntegrationTestSupport.assertEquals(0, trackedItem.getIntOr("slot", -1), "tracked view inventory slot");
        IntegrationTestSupport.assertEquals("minecraft:stone", trackedItem.getStringOr("id", ""), "tracked view inventory item id");
        IntegrationTestSupport.assertEquals(1, trackedItem.getIntOr("count", -1), "tracked view inventory count");
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

    private static void trackedChunkHashIncludesVerifierVisibleBlockEntityInventory() throws Exception
    {
        CompoundTag firstBlockEntity = inventoryBlockEntity("minecraft:stone", "Tracked Name");
        CompoundTag secondBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        LvcChunk first = singleBlockEntityChunk(firstBlockEntity);
        LvcChunk second = singleBlockEntityChunk(secondBlockEntity);

        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeHashContent(first), LvcChunkCodec.encodeHashContent(second)), "canonical full content bytes should keep inventory payload");
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(first), LvcChunkCodec.encodeTrackedContent(second)), "tracked bytes should include inventory payload");

        CompoundTag countBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        countBlockEntity.put("Items", itemList(2, "minecraft:dirt"));
        LvcChunk count = singleBlockEntityChunk(countBlockEntity);
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(count)), "tracked bytes should include inventory item counts");

        CompoundTag movedBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        movedBlockEntity.getListOrEmpty("Items").getCompoundOrEmpty(0).putByte("Slot", (byte) 1);
        LvcChunk moved = singleBlockEntityChunk(movedBlockEntity);
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(moved)), "tracked bytes should include inventory item slots");

        CompoundTag itemNbtBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        itemNbtBlockEntity.getListOrEmpty("Items").getCompoundOrEmpty(0).putString("custom_data", "not shown by verifier");
        LvcChunk itemNbt = singleBlockEntityChunk(itemNbtBlockEntity);
        IntegrationTestSupport.assertTrue(Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(itemNbt)), "tracked bytes should ignore item NBT that verifier does not report");

        CompoundTag timerBlockEntity = inventoryBlockEntity("minecraft:dirt", "Tracked Name");
        timerBlockEntity.putInt("CookTime", 42);
        timerBlockEntity.putInt("BurnTime", 12);
        LvcChunk timer = singleBlockEntityChunk(timerBlockEntity);
        IntegrationTestSupport.assertTrue(Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(timer)), "tracked bytes should ignore non-inventory block entity NBT");

        CompoundTag renamedBlockEntity = inventoryBlockEntity("minecraft:dirt", "Renamed");
        LvcChunk renamed = singleBlockEntityChunk(renamedBlockEntity);
        IntegrationTestSupport.assertTrue(Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(renamed)), "tracked bytes should ignore custom names that verifier does not report");
        IntegrationTestSupport.assertTrue(!Arrays.equals(LvcChunkCodec.encodeHashContent(second), LvcChunkCodec.encodeHashContent(renamed)), "full content bytes should still store non-inventory block entity NBT");

        CompoundTag componentBlockEntity = new CompoundTag();
        CompoundTag components = new CompoundTag();
        ListTag container = new ListTag();
        CompoundTag slot = new CompoundTag();
        CompoundTag item = new CompoundTag();

        item.putString("id", "minecraft:dirt");
        item.putInt("count", 1);
        slot.putInt("slot", 0);
        slot.put("item", item);
        container.add(slot);
        components.put("minecraft:container", container);
        componentBlockEntity.putString("id", "minecraft:furnace");
        componentBlockEntity.put("components", components);
        LvcChunk component = singleBlockEntityChunk(componentBlockEntity);
        IntegrationTestSupport.assertTrue(Arrays.equals(LvcChunkCodec.encodeTrackedContent(second), LvcChunkCodec.encodeTrackedContent(component)), "tracked bytes should normalize component-backed inventories");
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
}
