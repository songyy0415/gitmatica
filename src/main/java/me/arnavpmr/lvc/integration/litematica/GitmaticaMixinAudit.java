package me.arnavpmr.lvc.integration.litematica;

import me.arnavpmr.lvc.Gitmatica;

/**
 * Development-only eager target loading. Enable with
 * {@code -PgitmaticaAuditMixins}; normal players pay no runtime cost.
 */
public final class GitmaticaMixinAudit
{
    private static final String[] TARGET_CLASSES = {
            "fi.dy.masa.litematica.data.EntityDataManager",
            "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys",
            "fi.dy.masa.litematica.gui.GuiMainMenu",
            "fi.dy.masa.litematica.gui.GuiSchematicSaveBase",
            "fi.dy.masa.litematica.gui.widgets.WidgetSchematicEntry",
            "fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement",
            "fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult$BlockMismatchInfo",
            "fi.dy.masa.litematica.network.ServuxLitematicaHandler",
            "fi.dy.masa.litematica.render.OverlayRenderer",
            "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo",
            "fi.dy.masa.litematica.render.schematic.WorldRendererSchematic",
            "fi.dy.masa.litematica.scheduler.TaskScheduler",
            "fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler",
            "fi.dy.masa.litematica.schematic.placement.PlacementManagerTask",
            "fi.dy.masa.litematica.schematic.placement.PlacementManagerTaskRebuild",
            "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier",
            "net.minecraft.client.gui.render.GuiRenderer",
            "fi.dy.masa.malilib.render.InventoryOverlay",
            "net.minecraft.client.gui.Gui",
            "net.minecraft.client.gui.GuiGraphicsExtractor",
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
            "net.minecraft.client.multiplayer.ClientPacketListener",
            "net.minecraft.client.multiplayer.MultiPlayerGameMode"
    };

    private GitmaticaMixinAudit()
    {
    }

    public static void runIfRequested()
    {
        if (!Boolean.getBoolean("gitmatica.auditMixins"))
        {
            return;
        }

        ClassLoader loader = GitmaticaMixinAudit.class.getClassLoader();

        for (String targetClass : TARGET_CLASSES)
        {
            try
            {
                Class.forName(targetClass, false, loader);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalStateException(
                        "Gitmatica mixin audit could not load " + targetClass, e);
            }
        }

        Gitmatica.LOGGER.info(
                "Gitmatica mixin audit loaded {} target classes",
                TARGET_CLASSES.length);
    }
}
