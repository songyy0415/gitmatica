package me.niicide.lvc.config;

import me.niicide.lvc.gui.LvcHotkeyActions;

import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;

public final class LvcKeyCallbacks
{
    private LvcKeyCallbacks()
    {
    }

    public static void init()
    {
        IHotkeyCallback callback = new ProjectHotkeyCallback();

        for (var hotkey : LvcHotkeys.HOTKEY_LIST)
        {
            hotkey.getKeybind().setCallback(callback);
        }
    }

    private static final class ProjectHotkeyCallback implements IHotkeyCallback
    {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key)
        {
            return LvcHotkeyActions.handle(key);
        }
    }
}
