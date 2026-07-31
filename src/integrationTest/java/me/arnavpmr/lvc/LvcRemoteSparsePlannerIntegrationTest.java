package me.arnavpmr.lvc;

import static me.arnavpmr.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.arnavpmr.lvc.LvcIntegrationFixtures.inventoryBlockEntity;
import static me.arnavpmr.lvc.LvcIntegrationFixtures.objectId;
import static me.arnavpmr.lvc.LvcIntegrationFixtures.placementAt;
import static me.arnavpmr.lvc.LvcIntegrationFixtures.player;
import static me.arnavpmr.lvc.LvcIntegrationFixtures.singleLineSite;
import static me.arnavpmr.lvc.LvcSemanticTestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import me.arnavpmr.lvc.capture.LvcMinecraftWorldReader;
import me.arnavpmr.lvc.model.LvcChunkCoordinate;
import me.arnavpmr.lvc.model.LvcIntPosition;
import me.arnavpmr.lvc.model.LvcManifest;
import me.arnavpmr.lvc.semantic.LvcSemanticSchematicBuilder;
import me.arnavpmr.lvc.storage.LvcCanonicalNbt;
import me.arnavpmr.lvc.storage.LvcChunkStore;
import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.task.LvcRemoteSparseTargetPlanner;
import me.arnavpmr.lvc.world.LvcWorldBackend;

final class LvcRemoteSparsePlannerIntegrationTest
{
    private LvcRemoteSparsePlannerIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("remote Servux sparse planner tracks verifier-visible block entity inventory", LvcRemoteSparsePlannerIntegrationTest::remoteServuxSparsePlannerTracksVerifierVisibleBlockEntityInventory);
        IntegrationTestSupport.run("remote Servux sparse planner requires authoritative inventory after reconnect", LvcRemoteSparsePlannerIntegrationTest::remoteServuxSparsePlannerRequiresAuthoritativeInventoryAfterReconnect);
        IntegrationTestSupport.run("remote command sparse planner builds block state only schematic", LvcRemoteSparsePlannerIntegrationTest::remoteCommandSparsePlannerBuildsBlockStateOnlySchematic);
        IntegrationTestSupport.run("remote command sparse planner skips untracked gaps", LvcRemoteSparsePlannerIntegrationTest::remoteCommandSparsePlannerSkipsUntrackedGaps);
        IntegrationTestSupport.run("remote command sparse planner rejects unreadable blocks", LvcRemoteSparsePlannerIntegrationTest::remoteCommandSparsePlannerRejectsUnreadableBlocks);
    }

    private static void remoteServuxSparsePlannerTracksVerifierVisibleBlockEntityInventory() throws Exception
    {
        bootstrapMinecraft();

        Path repoDir = Files.createTempDirectory("lvc-servux-sparse-be-");
        String furnace = LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState());
        FakeWorldReader targetReader = new FakeWorldReader("minecraft:stone");
        targetReader.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        targetReader.setBlockEntity(new LvcIntPosition(0, 0, 0), inventoryBlockEntity("minecraft:stone", "Stored Name"));
        targetReader.setBlock(new LvcIntPosition(1, 0, 0), furnace);
        targetReader.setBlockEntity(new LvcIntPosition(1, 0, 0), inventoryBlockEntity("minecraft:diamond", "Stored Inventory"));
        targetReader.setBlock(new LvcIntPosition(2, 0, 0), furnace);
        targetReader.setBlock(new LvcIntPosition(3, 0, 0), furnace);
        targetReader.setBlockEntity(new LvcIntPosition(3, 0, 0), inventoryBlockEntity("minecraft:gold_ingot", "Stored Inventory"));
        LvcSemanticRepository.CommitResult init = LvcSemanticRepository.initProject(repoDir, "Servux Sparse BE",
                singleLineSite(4), placementAt(0, 0, 0), targetReader, player("ServuxSparseBe"));

        FakeWorldReader liveReader = new FakeWorldReader("minecraft:stone");
        CompoundTag renamedTimer = inventoryBlockEntity("minecraft:stone", "Renamed");
        renamedTimer.putInt("CookTime", 42);
        renamedTimer.putInt("BurnTime", 12);
        liveReader.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        liveReader.setBlockEntity(new LvcIntPosition(0, 0, 0), renamedTimer);
        liveReader.setBlock(new LvcIntPosition(1, 0, 0), furnace);
        liveReader.setBlockEntity(new LvcIntPosition(1, 0, 0), inventoryBlockEntity("minecraft:dirt", "Live Inventory"));
        liveReader.setBlock(new LvcIntPosition(2, 0, 0), furnace);
        liveReader.setBlockEntity(new LvcIntPosition(2, 0, 0), inventoryBlockEntity("minecraft:emerald", "Unexpected Inventory"));
        liveReader.setBlock(new LvcIntPosition(3, 0, 0), furnace);

        LvcRemoteSparseTargetPlanner planner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.SERVUX, liveReader, init.manifest().site("main"));
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

        IntegrationTestSupport.assertNotNull(container, "Servux sparse BE test should create a region container");
        IntegrationTestSupport.assertEquals(Blocks.STRUCTURE_VOID.defaultBlockState(), container.get(0, 0, 0), "non-inventory BE NBT drift should be skipped");
        IntegrationTestSupport.assertEquals(Blocks.FURNACE.defaultBlockState(), container.get(1, 0, 0), "changed inventory should be included");
        IntegrationTestSupport.assertEquals(Blocks.FURNACE.defaultBlockState(), container.get(2, 0, 0), "unexpected live inventory should be included");
        IntegrationTestSupport.assertEquals(Blocks.FURNACE.defaultBlockState(), container.get(3, 0, 0), "missing live inventory should be included");
        IntegrationTestSupport.assertEquals(4, planner.scannedBlocks(), "Servux sparse BE scanned blocks");
        IntegrationTestSupport.assertEquals(0, planner.stateMismatches(), "Servux sparse BE state mismatches");
        IntegrationTestSupport.assertEquals(3, planner.blockEntityMismatches(), "Servux sparse should only count verifier-visible inventory diffs");
        IntegrationTestSupport.assertEquals(0, planner.ignoredBlockEntityTargets(), "Servux sparse should not ignore block entity targets");
        IntegrationTestSupport.assertEquals(4, liveReader.blockEntityReadCount(), "Servux sparse should read live BE NBT for tracked inventory comparison");
        IntegrationTestSupport.assertEquals(3, session.includedBlocks(), "Servux sparse included BE-dirty blocks");
        IntegrationTestSupport.assertEquals(1, session.structureVoidBlocks(), "Servux sparse skipped non-visible BE drift");
    }

    private static void remoteServuxSparsePlannerRequiresAuthoritativeInventoryAfterReconnect() throws Exception
    {
        bootstrapMinecraft();

        String furnace = LvcMinecraftWorldReader.blockStateString(Blocks.FURNACE.defaultBlockState());
        CompoundTag emptyInventory = new CompoundTag();
        emptyInventory.putString("id", "minecraft:furnace");
        emptyInventory.put("Items", new ListTag());
        LvcSemanticSchematicBuilder.TargetBlock target = new LvcSemanticSchematicBuilder.TargetBlock(
                new LvcChunkCoordinate(0, 0, 0),
                0,
                new LvcIntPosition(0, 0, 0),
                new LvcIntPosition(0, 0, 0),
                BlockPos.ZERO,
                furnace,
                LvcCanonicalNbt.encodeBlockEntity(emptyInventory)
        );

        FakeWorldReader unavailableAfterReconnect = new FakeWorldReader("minecraft:stone");
        unavailableAfterReconnect.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        LvcRemoteSparseTargetPlanner stalePlanner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.SERVUX, unavailableAfterReconnect, singleLineSite());

        IntegrationTestSupport.assertTrue(!stalePlanner.include(target, Blocks.FURNACE.defaultBlockState()),
                "missing post-reconnect inventory is indistinguishable from an empty target");

        FakeWorldReader authoritativeReader = new FakeWorldReader("minecraft:stone");
        authoritativeReader.setBlock(new LvcIntPosition(0, 0, 0), furnace);
        authoritativeReader.setBlockEntity(new LvcIntPosition(0, 0, 0),
                inventoryBlockEntity("minecraft:diamond", "Server Inventory"));
        LvcRemoteSparseTargetPlanner authoritativePlanner = new LvcRemoteSparseTargetPlanner(
                LvcWorldBackend.SERVUX, authoritativeReader, singleLineSite());

        IntegrationTestSupport.assertTrue(authoritativePlanner.include(target, Blocks.FURNACE.defaultBlockState()),
                "fresh Servux inventory should include the dirty container in the sparse paste");
        IntegrationTestSupport.assertEquals(1, authoritativePlanner.blockEntityMismatches(),
                "fresh Servux inventory mismatch count");
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
}
