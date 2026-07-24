package me.niicide.lvc.semantic;

import java.util.List;
import net.minecraft.core.BlockPos;
import me.niicide.lvc.model.LvcManifest;

public record LvcProjectEditorState(String projectName, BlockPos placementOrigin, List<LvcManifest.Region> regions)
{
    public LvcProjectEditorState
    {
        regions = List.copyOf(regions);
    }
}
