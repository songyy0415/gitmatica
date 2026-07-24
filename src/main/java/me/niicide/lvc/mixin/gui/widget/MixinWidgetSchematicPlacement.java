package me.niicide.lvc.mixin.gui.widget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.litematica.gui.widgets.WidgetListSchematicPlacements;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import me.niicide.lvc.gui.GitmaticaIcons;
import me.niicide.lvc.gui.LvcSchematicPlacementRowActions;

@Mixin(WidgetSchematicPlacement.class)
abstract class MixinWidgetSchematicPlacement extends WidgetListEntryBase<SchematicPlacement>
{
    private static final int GITMATICA_BUTTON_GAP = 2;

    protected MixinWidgetSchematicPlacement(
            int x,
            int y,
            int width,
            int height,
            SchematicPlacement entry,
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
            SchematicPlacement placement,
            int listIndex,
            WidgetListSchematicPlacements parent,
            CallbackInfo callbackInfo)
    {
        if (!LvcSchematicPlacementRowActions.isLvcTrackingOverlay(placement))
        {
            return;
        }

        this.subWidgets.clear();
        int right = x + width - 2;
        int buttonY = y + 1;
        ButtonOnOff enabled = new ButtonOnOff(
                right,
                buttonY,
                -1,
                true,
                "litematica.gui.button.schematic_placements.placement_enabled",
                placement.isEnabled());
        this.addButton(enabled, (pressed, mouseButton) -> {
            placement.toggleEnabled();
            parent.refreshEntries();
        });
        right = enabled.getX() - GITMATICA_BUTTON_GAP;
        right = this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.schematic_placements.lvc_edit",
                () -> LvcSchematicPlacementRowActions.openProjectEditor(placement));
        right = this.gitmatica$addButton(
                right,
                buttonY,
                "gitmatica.gui.button.schematic_placements.lvc_manage",
                () -> LvcSchematicPlacementRowActions.openProjectManager(placement));
        ((WidgetSchematicPlacement) (Object) this).buttonsStartX = right;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gitmatica$renderTrackingOverlayIcon(
            GuiContext context,
            int mouseX,
            int mouseY,
            boolean selected,
            CallbackInfo callbackInfo)
    {
        SchematicPlacement placement = ((WidgetSchematicPlacement) (Object) this).placement;

        if (LvcSchematicPlacementRowActions.isLvcTrackingOverlay(placement))
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
        return button.getX() - GITMATICA_BUTTON_GAP;
    }
}
