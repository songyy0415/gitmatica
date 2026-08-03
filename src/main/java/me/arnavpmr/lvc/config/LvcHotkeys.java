package me.arnavpmr.lvc.config;

import java.util.List;
import com.google.common.collect.ImmutableList;
import me.arnavpmr.lvc.LvcReference;

import fi.dy.masa.malilib.config.options.ConfigHotkey;

public final class LvcHotkeys
{
    private static final String HOTKEYS_KEY = LvcReference.MOD_ID + ".config.hotkeys";

    public static final ConfigHotkey OPEN_PROJECT_BROWSER = new ConfigHotkey("openProjectBrowser", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey OPEN_PROJECT_MANAGER = new ConfigHotkey("openProjectManager", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey OPEN_PROJECT_EDITOR = new ConfigHotkey("openProjectEditor", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey DISCARD_CHANGES = new ConfigHotkey("discardChanges", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey CLEAR_AREA = new ConfigHotkey("clearArea", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey SAVE_VERSION = new ConfigHotkey("saveVersion", "").apply(HOTKEYS_KEY);
    public static final ConfigHotkey UNDO_LAST_SAVE = new ConfigHotkey("undoLastSave", "").apply(HOTKEYS_KEY);

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_PROJECT_BROWSER,
            OPEN_PROJECT_MANAGER,
            OPEN_PROJECT_EDITOR,
            DISCARD_CHANGES,
            CLEAR_AREA,
            SAVE_VERSION,
            UNDO_LAST_SAVE
    );

    private LvcHotkeys()
    {
    }
}
