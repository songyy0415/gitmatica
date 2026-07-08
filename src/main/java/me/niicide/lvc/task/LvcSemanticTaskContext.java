package me.niicide.lvc.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.world.level.Level;
import me.niicide.lvc.LvcProjectService;
import me.niicide.lvc.LvcUserActionException;
import me.niicide.lvc.capture.LvcMinecraftWorldReader;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;
import me.niicide.lvc.overlay.LvcTrackingOverlayService;
import me.niicide.lvc.storage.LvcSemanticRepository;

final class LvcSemanticTaskContext
{
    private LvcSemanticTaskContext()
    {
    }

    static ActiveProject readActiveProject(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!LvcProjectService.isSemanticProject(repositoryDirectory))
        {
            throw new IOException("This LVC operation requires a semantic LVC project");
        }

        LvcManifest manifest = LvcSemanticRepository.readManifest(repositoryDirectory);
        String siteId = LvcSemanticRepository.defaultSiteId(manifest);
        LvcManifest.Site site = manifest.site(siteId);
        LvcSitePlacement placement = LvcTrackingOverlayService.requireSitePlacement(repositoryDirectory, site);

        if (site.regions().isEmpty())
        {
            throw new IOException("LVC project has no tracked sub-regions");
        }

        return new ActiveProject(manifest, siteId, site, placement);
    }

    static void validatePlacementDimension(LvcSitePlacement placement, Level world) throws IOException
    {
        String worldDimension = LvcMinecraftWorldReader.dimensionId(world);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new LvcUserActionException(LvcUserActionException.Reason.WRONG_DIMENSION,
                    "Active LVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }
    }

    record ActiveProject(LvcManifest manifest, String siteId,
                         LvcManifest.Site site, LvcSitePlacement placement)
    {
    }
}
