package me.niicide.lvc.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import me.niicide.lvc.model.LvcManifest;

public final class LvcManifestJsonCodec
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LvcManifestJsonCodec()
    {
    }

    public static LvcManifest decode(String json)
    {
        ManifestJson manifest = GSON.fromJson(json, ManifestJson.class);

        if (manifest == null)
        {
            throw new IllegalArgumentException("LVC manifest JSON is empty");
        }

        List<LvcManifest.Site> sites = new ArrayList<>();

        for (SiteJson site : requireNotNull(manifest.sites(), "sites"))
        {
            sites.add(new LvcManifest.Site(
                    site.id(),
                    site.name(),
                    site.dimension(),
                    site.regions(),
                    site.hashIndex(),
                    Map.of(),
                    Map.of()
            ));
        }

        return new LvcManifest(
                manifest.format(),
                manifest.name(),
                LvcManifest.Content.defaultContent(),
                sites
        ).validate();
    }

    public static String encode(LvcManifest manifest)
    {
        List<SiteJson> sites = new ArrayList<>(manifest.sites().size());

        for (LvcManifest.Site site : manifest.sites())
        {
            sites.add(new SiteJson(site.id(), site.name(), site.dimension(), site.regions(), site.hashIndex()));
        }

        return GSON.toJson(new ManifestJson(manifest.format(), manifest.name(), sites));
    }

    public static boolean hasLegacySerializedContent(String json)
    {
        JsonElement element = JsonParser.parseString(json);
        return element != null && element.isJsonObject() && element.getAsJsonObject().has("content");
    }

    private static <T> T requireNotNull(T value, String label)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("LVC " + label + " must not be null");
        }

        return value;
    }

    private record ManifestJson(String format, String name, List<SiteJson> sites)
    {
    }

    private record SiteJson(String id, String name, String dimension, List<LvcManifest.Region> regions,
                            @SerializedName("hash_index") String hashIndex)
    {
    }
}
