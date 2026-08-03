package me.arnavpmr.lvc.overlay;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;

public record LvcTrackingOverlay(SchematicPlacement placement, SchematicVerifier verifier, boolean verifierStarted)
{
}
