package me.niicide.lvc.gui;

import java.util.List;
import me.niicide.lvc.LvcReference;
import me.niicide.lvc.config.LvcHotkeys;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiLvcConfigs extends GuiConfigsBase
{
    public GuiLvcConfigs()
    {
        super(10, 50, LvcReference.MOD_ID, null, "gitmatica.gui.title.configs", LvcReference.MOD_VERSION);
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearOptions();

        ButtonGeneric button = new ButtonGeneric(
                10,
                26,
                -1,
                20,
                StringUtils.translate("litematica.gui.button.config_gui.hotkeys")
        );
        button.setEnabled(false);
        this.addButton(button, (clickedButton, mouseButton) -> { });
    }

    @Override
    protected boolean useKeybindSearch()
    {
        return true;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs()
    {
        return ConfigOptionWrapper.createFor(LvcHotkeys.HOTKEY_LIST);
    }
}
