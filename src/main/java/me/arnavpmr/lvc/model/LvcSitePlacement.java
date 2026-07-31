package me.arnavpmr.lvc.model;

import java.util.List;
import java.util.Objects;

public record LvcSitePlacement(String dimension, List<Integer> origin)
{
    public LvcSitePlacement
    {
        if (dimension == null || dimension.isBlank())
        {
            throw new IllegalArgumentException("LVC site placement dimension must not be blank");
        }

        origin = List.copyOf(Objects.requireNonNull(origin, "origin"));

        if (origin.size() != 3)
        {
            throw new IllegalArgumentException("LVC site placement origin must have three coordinates");
        }

        for (Integer value : origin)
        {
            if (value == null)
            {
                throw new IllegalArgumentException("LVC site placement origin contains null coordinate");
            }
        }
    }
}
