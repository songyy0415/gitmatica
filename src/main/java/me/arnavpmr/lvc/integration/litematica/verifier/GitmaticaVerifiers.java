package me.arnavpmr.lvc.integration.litematica.verifier;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;

public final class GitmaticaVerifiers
{
    private GitmaticaVerifiers()
    {
    }

    public static GitmaticaVerifier extension(SchematicVerifier verifier)
    {
        if (verifier instanceof GitmaticaVerifier extension)
        {
            return extension;
        }

        throw new IllegalStateException("Gitmatica verifier mixin was not applied");
    }
}
