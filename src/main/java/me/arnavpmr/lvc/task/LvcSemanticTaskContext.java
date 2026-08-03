package me.arnavpmr.lvc.task;

import me.arnavpmr.lvc.storage.LvcSemanticRepository;
import me.arnavpmr.lvc.overlay.LvcTrackingOverlayService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.world.level.Level;
import me.arnavpmr.lvc.LvcUserActionException;
import me.arnavpmr.lvc.capture.LvcMinecraftWorldReader;
import me.arnavpmr.lvc.model.LvcManifest;
import me.arnavpmr.lvc.model.LvcSitePlacement;

final class LvcSemanticTaskContext
{
    private LvcSemanticTaskContext()
    {
    }

    static ActiveProject readActiveProject(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!LvcSemanticRepository.isSemanticProject(repositoryDirectory))
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
