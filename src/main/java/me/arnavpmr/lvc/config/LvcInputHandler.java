package me.arnavpmr.lvc.config;

import me.arnavpmr.lvc.LvcReference;

import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

public final class LvcInputHandler implements IKeybindProvider
{
    private static final LvcInputHandler INSTANCE = new LvcInputHandler();

    private LvcInputHandler()
    {
    }

    public static LvcInputHandler getInstance()
    {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager)
    {
        for (IHotkey hotkey : LvcHotkeys.HOTKEY_LIST)
        {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager)
    {
        manager.addHotkeysForCategory(LvcReference.MOD_NAME,
                LvcReference.MOD_ID + ".hotkeys.category.project", LvcHotkeys.HOTKEY_LIST);
    }
}
