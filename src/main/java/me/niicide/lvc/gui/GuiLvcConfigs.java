package me.niicide.lvc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.niicide.lvc.LvcReference;
import me.niicide.lvc.config.LvcConfigs;
import me.niicide.lvc.config.LvcHotkeys;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IConfigGuiAllTab;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcConfigs extends GuiConfigsBase implements IConfigGuiAllTab
{
    private ConfigGuiTab selectedTab = ConfigGuiTab.GENERIC;

    public GuiLvcConfigs()
    {
        super(10, 50, LvcReference.MOD_ID, null, "gitmatica.gui.title.configs", LvcReference.MOD_VERSION);
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;

        for (ConfigGuiTab tab : ConfigGuiTab.values())
        {
            x += this.createButton(x, y, tab);
        }
    }

    @Override
    protected boolean useKeybindSearch()
    {
        return this.selectedTab == ConfigGuiTab.ALL || this.selectedTab == ConfigGuiTab.HOTKEYS;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs()
    {
        if (this.selectedTab == ConfigGuiTab.ALL)
        {
            return this.getAllConfigs();
        }

        List<? extends IConfigBase> configs;

        if (this.selectedTab == ConfigGuiTab.GENERIC)
        {
            configs = LvcConfigs.Generic.OPTIONS;
        }
        else
        {
            configs = LvcHotkeys.HOTKEY_LIST;
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    @Override
    public boolean useAllTab()
    {
        return true;
    }

    @Override
    public List<ConfigOptionWrapper> getAllConfigs()
    {
        List<ConfigOptionWrapper> configs = new ArrayList<>();
        configs.addAll(ConfigOptionWrapper.createFor(LvcConfigs.Generic.OPTIONS));
        configs.addAll(ConfigOptionWrapper.createFor(LvcHotkeys.HOTKEY_LIST));
        return configs;
    }

    private int createButton(int x, int y, ConfigGuiTab tab)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, tab.getDisplayName());
        button.setEnabled(this.selectedTab != tab);
        this.addButton(button, new ButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    private enum ConfigGuiTab
    {
        ALL(IConfigGuiAllTab.getTranslationKey()),
        GENERIC("litematica.gui.button.config_gui.generic"),
        HOTKEYS("litematica.gui.button.config_gui.hotkeys");

        private final String translationKey;

        ConfigGuiTab(String translationKey)
        {
            this.translationKey = translationKey;
        }

        private String getDisplayName()
        {
            return StringUtils.translate(this.translationKey);
        }
    }

    private record ButtonListener(ConfigGuiTab tab, GuiLvcConfigs parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.parent.selectedTab = this.tab;
            this.parent.reCreateListWidget();
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
