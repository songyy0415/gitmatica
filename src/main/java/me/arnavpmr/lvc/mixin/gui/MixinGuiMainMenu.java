package me.arnavpmr.lvc.mixin.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import me.arnavpmr.lvc.gui.GitmaticaButtonIcons;
import me.arnavpmr.lvc.gui.GuiLvcProjectBrowser;

@Mixin(GuiMainMenu.class)
abstract class MixinGuiMainMenu extends GuiBase
{
    @Shadow
    private int getButtonWidth()
    {
        throw new AssertionError();
    }
    @Inject(method = "initGui", at = @At("TAIL"))
    private void gitmatica$addProjectBrowserButton(CallbackInfo callbackInfo)
    {
        int width = this.getButtonWidth();
        int x = 12 + width + 20;
        int y = 52;
        String label = StringUtils.translate(
                "gitmatica.gui.button.change_menu.lvc_project_browser");
        ButtonGeneric button = new ButtonGeneric(
                x,
                y,
                width,
                20,
                label,
                GitmaticaButtonIcons.FOLDER);

        this.addButton(button, (pressed, mouseButton) -> {
            GuiLvcProjectBrowser browser = new GuiLvcProjectBrowser();
            browser.setParent(this.getParent());
            GuiBase.openGui(browser);
        });
    }
}
