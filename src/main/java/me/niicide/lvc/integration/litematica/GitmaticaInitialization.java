package me.niicide.lvc.integration.litematica;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import me.niicide.lvc.LvcReference;
import me.niicide.lvc.config.LvcConfigs;
import me.niicide.lvc.config.LvcInputHandler;
import me.niicide.lvc.config.LvcKeyCallbacks;
import me.niicide.lvc.gui.GuiLvcConfigs;

/**
 * Owns Gitmatica's public registrations. Litematica internals are integrated
 * separately through narrow mixins, never by replacing Litematica's initializer.
 */
public final class GitmaticaInitialization implements IInitializationHandler
{
    @Override
    public void registerModHandlers()
    {
        LvcConfigs.init();
        ConfigManager.getInstance().registerConfigHandler(LvcReference.MOD_ID, new LvcConfigs());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(LvcReference.MOD_ID, LvcReference.MOD_NAME, GuiLvcConfigs::new));

        InputEventHandler.getKeybindManager().registerKeybindProvider(LvcInputHandler.getInstance());
        LvcKeyCallbacks.init();

        RenderEventHandler.getInstance().registerInGameGuiRenderer(new GitmaticaHudRenderer());

        GitmaticaWorldLoadListener worldLoadListener = new GitmaticaWorldLoadListener();
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(worldLoadListener);
        WorldLoadHandler.getInstance().registerWorldLoadPostHandler(worldLoadListener);
    }
}
