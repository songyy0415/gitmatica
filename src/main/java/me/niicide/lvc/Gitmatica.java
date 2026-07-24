package me.niicide.lvc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import fi.dy.masa.malilib.event.InitializationHandler;
import me.niicide.lvc.config.LvcConfigs;
import me.niicide.lvc.integration.litematica.GitmaticaInitialization;
import me.niicide.lvc.integration.litematica.GitmaticaMixinAudit;

public final class Gitmatica implements ModInitializer
{
    public static final Logger LOGGER = LogManager.getLogger(LvcReference.MOD_ID);
    public static final String MOD_VERSION = FabricLoader.getInstance()
            .getModContainer(LvcReference.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    @Override
    public void onInitialize()
    {
        InitializationHandler.getInstance().registerInitializationHandler(new GitmaticaInitialization());
        GitmaticaMixinAudit.runIfRequested();
    }

    public static void debugLog(String message, Object... arguments)
    {
        if (LvcConfigs.isDebugLoggingEnabled())
        {
            LOGGER.info("[DEBUG] " + message, arguments);
        }
    }
}
