package me.arnavpmr.lvc.integration.litematica;

import java.util.function.Supplier;

import net.minecraft.util.profiling.ProfilerFiller;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import me.arnavpmr.lvc.LvcReference;
import me.arnavpmr.lvc.gui.LvcGuiMessages;

final class GitmaticaHudRenderer implements IRenderer
{
    @Override
    public void onExtractGuiOverlayPost(GuiContext context, float partialTicks, ProfilerFiller profiler)
    {
        LvcGuiMessages.renderInGameMessages(context);
    }

    @Override
    public Supplier<String> getProfilerSectionSupplier()
    {
        return () -> LvcReference.MOD_ID + "_render_handler";
    }
}
