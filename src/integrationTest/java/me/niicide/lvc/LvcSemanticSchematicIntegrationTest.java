package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.blockPosToList;
import static me.niicide.lvc.LvcIntegrationFixtures.bootstrapMinecraft;
import static me.niicide.lvc.LvcIntegrationFixtures.objectId;
import static me.niicide.lvc.LvcIntegrationFixtures.placementAt;
import static me.niicide.lvc.LvcIntegrationFixtures.player;
import static me.niicide.lvc.LvcIntegrationFixtures.singleLineSite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import org.eclipse.jgit.api.Git;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcIntPosition;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.storage.LvcChunkStore;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcSemanticSchematicIntegrationTest
{
    private LvcSemanticSchematicIntegrationTest()
    {
    }

    static void runAll() throws Exception
    {
        IntegrationTestSupport.run("semantic working tree schematic loads full hashes", LvcSemanticSchematicIntegrationTest::semanticWorkingTreeSchematicLoadsFullHashes);
        IntegrationTestSupport.run("semantic sparse schematic uses structure void for skipped blocks", LvcSemanticSchematicIntegrationTest::semanticSparseSchematicUsesStructureVoidForSkippedBlocks);
        IntegrationTestSupport.run("semantic working tree schematic loads block entity NBT", LvcSemanticSchematicIntegrationTest::semanticWorkingTreeSchematicLoadsBlockEntityNbt);
        IntegrationTestSupport.run("semantic working tree schematic promotes container components", LvcSemanticSchematicIntegrationTest::semanticWorkingTreeSchematicPromotesContainerComponents);
        IntegrationTestSupport.run("semantic commit export uses selected commit", LvcSemanticSchematicIntegrationTest::semanticCommitExportUsesSelectedCommit);
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
}
