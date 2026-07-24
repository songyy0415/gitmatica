package me.niicide.lvc.mixin.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.gui.GuiSchematicSave;
import fi.dy.masa.litematica.gui.GuiSchematicSaveBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import me.niicide.lvc.gui.LvcCreateProjectAction;

@Mixin(GuiSchematicSaveBase.class)
abstract class MixinGuiSchematicSaveBase
{
    @Inject(method = "initGui", at = @At("TAIL"))
    private void gitmatica$addCreateProjectButton(CallbackInfo callbackInfo)
    {
        GuiSchematicSaveBase gui = (GuiSchematicSaveBase) (Object) this;

        if (!(gui instanceof GuiSchematicSave))
        {
            return;
        }

        String saveLabel = StringUtils.translate("litematica.gui.button.save_schematic");
        String createLabel = StringUtils.translate(
                "gitmatica.gui.button.lvc_project.create");
        int x = 10 + gui.getStringWidth(saveLabel) + 14;
        ButtonGeneric button = new ButtonGeneric(
                x,
                54,
                gui.getStringWidth(createLabel) + 10,
                20,
                createLabel);

        gui.addButton(
                button,
                (pressed, mouseButton) ->
                        LvcCreateProjectAction.promptFromSaveGui(gui));
    }
}
