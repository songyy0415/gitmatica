package me.arnavpmr.lvc.integration.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.arnavpmr.lvc.gui.GuiLvcConfigs;

public final class GitmaticaModMenuIntegration implements ModMenuApi
{
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return parent -> {
            GuiLvcConfigs gui = new GuiLvcConfigs();
            gui.setParent(parent);
            return gui;
        };
    }
}
