package me.arnavpmr.lvc.mixin.gui.widget;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.gui.widgets.WidgetListLoadedSchematics;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import me.arnavpmr.lvc.gui.GitmaticaIcons;
import me.arnavpmr.lvc.gui.LvcSchematicPlacementRowActions;

@Mixin(WidgetSchematicEntry.class)
abstract class MixinWidgetSchematicEntry extends WidgetListEntryBase<LitematicaSchematic>
{
    @Shadow @Final private WidgetListLoadedSchematics parent;
    @Shadow @Final private LitematicaSchematic schematic;
    @Shadow @Final @Mutable private int buttonsStartX;

    protected MixinWidgetSchematicEntry(
            int x,
            int y,
            int width,
            int height,
            LitematicaSchematic entry,
            int listIndex)
    {
        super(x, y, width, height, entry, listIndex);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gitmatica$replaceTrackingOverlayActions(
            int x,
            int y,
            int width,
            int height,
            boolean odd,
            LitematicaSchematic schematic,
            int listIndex,
            WidgetListLoadedSchematics parent,
            CallbackInfo callbackInfo)
    {
        if (!LvcSchematicPlacementRowActions.isLvcTrackingOverlay(schematic))
        {
            return;
        }

        this.subWidgets.clear();
        int buttonY = y + 1;
        int right = x + width;
        right -= this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.loaded_schematics.lvc_close",
                () -> {
                    if (LvcSchematicPlacementRowActions.closeProject(this.schematic))
                    {
                        this.parent.refreshEntries();
                    }
                });
        right -= this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.lvc_project.export",
                () -> LvcSchematicPlacementRowActions.exportLoadedOverlay(this.schematic));
        right -= this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.schematic_placements.lvc_edit",
                () -> LvcSchematicPlacementRowActions.openProjectEditor(this.schematic));
        right -= this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.schematic_placements.lvc_manage",
                () -> LvcSchematicPlacementRowActions.openProjectManager(this.schematic));
        this.buttonsStartX = right;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gitmatica$renderTrackingOverlayIcon(
            GuiContext context,
            int mouseX,
            int mouseY,
            boolean selected,
            CallbackInfo callbackInfo)
    {
        if (LvcSchematicPlacementRowActions.isLvcTrackingOverlay(this.schematic))
        {
            GitmaticaIcons.SCHEMATIC_TYPE_FILE.renderAt(
                    context,
                    this.x + 2,
                    this.y + 5,
                    this.zLevel,
                    false,
                    false);
        }
    }

    private int gitmatica$addButton(int right, int y, String translationKey, Runnable action)
    {
        ButtonGeneric button = new ButtonGeneric(
                right,
                y,
                -1,
                true,
                StringUtils.translate(translationKey));
        this.addButton(button, (pressed, mouseButton) -> action.run());
        return button.getWidth() + 1;
    }
}
