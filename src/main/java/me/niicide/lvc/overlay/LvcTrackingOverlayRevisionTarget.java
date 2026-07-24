package me.niicide.lvc.overlay;

import javax.annotation.Nullable;

public record LvcTrackingOverlayRevisionTarget(String headCommitId, @Nullable String sourceCommitId,
                                               boolean airSchematic)
{
}
