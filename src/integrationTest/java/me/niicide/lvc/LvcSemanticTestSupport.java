package me.niicide.lvc;

import static me.niicide.lvc.LvcIntegrationFixtures.objectId;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import me.niicide.lvc.capture.LvcCaptureEngine;
import me.niicide.lvc.model.LvcChunk;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.semantic.LvcSemanticSchematicBuilder;
import me.niicide.lvc.storage.LvcCanonicalNbt;
import me.niicide.lvc.storage.LvcChunkCodec;
import me.niicide.lvc.storage.LvcChunkStore;

final class LvcSemanticTestSupport
{
    private LvcSemanticTestSupport()
    {
    }

    static LvcManifest.Site validatedSingleSite(List<LvcManifest.Region> regions)
    {
        LvcManifest manifest = LvcManifest.create("Capture", List.of(new LvcManifest.Site("main", "Main", "minecraft:overworld", regions, Map.of())));
        return manifest.sites().get(0);
    }

    static void finishSchematicBuild(LvcSemanticSchematicBuilder.BuildSession session) throws Exception
    {
        while (!session.isComplete())
        {
            session.processNextChunk();
        }
    }

    static LvcManifest manifestWithObject(String name, String objectId)
    {
        return LvcManifest.create(name, List.of(new LvcManifest.Site(
                "main",
                "Main",
                "minecraft:overworld",
                List.of(new LvcManifest.Region("Line", List.of(0, 0, 0), List.of(1, 1, 1))),
                Map.of("0,0,0", objectId)
        )));
    }

    static LvcChunk readOnlyCapturedChunk(Path repoDir, LvcCaptureEngine.Result result) throws Exception
    {
        IntegrationTestSupport.assertEquals(1, result.fullHashes().size(), "expected exactly one captured chunk");
        String objectId = result.fullHashes().values().iterator().next();
        return LvcChunkCodec.decode(LvcChunkStore.readObject(repoDir, objectId));
    }

    static byte[] entityPayload(String id, double x, double y, double z) throws Exception
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        NbtUtils.putVec3dCodec(tag, new Vec3(x, y, z), "Pos");
        return LvcCanonicalNbt.encodeUnnamed(tag);
    }

    static Set<String> committedFiles(Repository repository, RevCommit commit) throws Exception
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
