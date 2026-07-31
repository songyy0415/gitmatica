package me.arnavpmr.lvc.project;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

public record LvcProjectSummary(String name, int versionCount, @Nullable BlockPos origin)
{
}
