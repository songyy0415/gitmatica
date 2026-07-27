package me.niicide.lvc.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import me.niicide.lvc.model.LvcManifest;
import me.niicide.lvc.model.LvcSitePlacement;

import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;

public final class LvcProjectSelectionStorage
{
    private LvcProjectSelectionStorage()
    {
    }

    public static LvcManifest.Site createMainSiteFromSelection(String siteName, String dimensionId, AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        validateProjectName(siteName);

        BlockPos origin = selection.getEffectiveOrigin();
        List<LvcManifest.Region> regions = createRegionsFromSelection(selection, origin);

        return new LvcManifest.Site("main", siteName, dimensionId, regions, Map.of());
    }

    public static List<LvcManifest.Region> createRegionsFromSelection(
            AreaSelection selection, BlockPos origin)
    {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(origin, "origin");

        List<Box> boxes = new ArrayList<>(getValidBoxes(selection));
        boxes.sort(Comparator.comparing(box -> box.getName() == null ? "" : box.getName()));

        if (boxes.isEmpty())
        {
            throw new IllegalArgumentException("LVC project has no valid area boxes");
        }

        List<LvcManifest.Region> regions = new ArrayList<>();
        Set<String> usedRegionNames = new HashSet<>();

        for (Box box : boxes)
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos min = PositionUtils.getMinCorner(pos1, pos2);
            BlockPos max = PositionUtils.getMaxCorner(pos1, pos2);
            BlockPos relativeMin = min.subtract(origin);
            BlockPos size = max.subtract(min).offset(1, 1, 1);
            String regionName = box.getName();

            if (regionName == null || regionName.isBlank())
            {
                throw new IllegalArgumentException("LVC sub-region name must not be blank");
            }

            if (!usedRegionNames.add(regionName))
            {
                throw new IllegalArgumentException(
                        "LVC sub-region names must be unique: " + regionName);
            }

            regions.add(new LvcManifest.Region(
                    regionName,
                    LvcProjectPositions.blockPosToList(relativeMin),
                    LvcProjectPositions.blockPosToList(size)
            ));
        }

        return List.copyOf(regions);
    }

    public static LvcSitePlacement createSitePlacement(BlockPos origin, String dimensionId)
    {
        return new LvcSitePlacement(dimensionId, LvcProjectPositions.blockPosToList(origin));
    }

    public static int countValidSelectionRegions(AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        return getValidBoxes(selection).size();
    }

    public static void validateProjectName(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            throw new IllegalArgumentException("LVC project name must not be blank");
        }
    }

    private static List<Box> getValidBoxes(AreaSelection selection)
    {
        return PositionUtils.getValidBoxes(selection);
    }

}
