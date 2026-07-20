package me.niicide.lvc.config;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.niicide.lvc.LvcDiagnostics;
import me.niicide.lvc.LvcReference;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

public final class LvcConfigs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = LvcReference.MOD_ID + ".json";

    public static void loadFromFile()
    {
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile))
        {
            JsonElement element = JsonUtils.parseJsonFile(configFile);

            if (element != null && element.isJsonObject())
            {
                ConfigUtils.readConfigBase(element.getAsJsonObject(), "Hotkeys", LvcHotkeys.HOTKEY_LIST);
            }
            else
            {
                LvcDiagnostics.warn("Failed to load Gitmatica config file '{}'", configFile.toAbsolutePath());
            }
        }
    }

    public static void saveToFile()
    {
        Path directory = FileUtils.getConfigDirectory();

        if (!Files.exists(directory))
        {
            FileUtils.createDirectoriesIfMissing(directory);
        }

        if (Files.isDirectory(directory))
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Hotkeys", LvcHotkeys.HOTKEY_LIST);
            JsonUtils.writeJsonToFile(root, directory.resolve(CONFIG_FILE_NAME));
        }
        else
        {
            LvcDiagnostics.warn("Gitmatica config directory does not exist: '{}'", directory.toAbsolutePath());
        }
    }

    @Override
    public void load()
    {
        loadFromFile();
    }

    @Override
    public void save()
    {
        saveToFile();
    }
}
