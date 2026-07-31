package me.arnavpmr.lvc.semantic;

import java.util.List;
import net.minecraft.core.BlockPos;
import me.arnavpmr.lvc.model.LvcManifest;

public record LvcProjectEditorState(String projectName, BlockPos placementOrigin, List<LvcManifest.Region> regions)
{
    public LvcProjectEditorState
    {
        regions = List.copyOf(regions);
    }
}
