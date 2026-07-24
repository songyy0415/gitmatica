package me.niicide.lvc.gui;

import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix3x2f;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.element.MaLiLibTexturedGuiElement;
import me.niicide.lvc.LvcReference;

/**
 * Gitmatica-owned icons. Keeping these outside Litematica's icon enums avoids
 * resource-atlas overrides and lets the addon coexist with an unmodified jar.
 */
public enum GitmaticaIcons implements IGuiIcon
{
    SCHEMATIC_TYPE_FILE(18, 1, 12, 12),
    UNDO(1, 35, 16, 16),
    ARROW_RIGHT(18, 35, 16, 16),
    DETACHED_HEAD(18, 52, 16, 16),
    CHECK(1, 69, 16, 16),
    BRANCH(1, 86, 16, 16);

    private static final int TEXTURE_WIDTH = 100;
    private static final int TEXTURE_HEIGHT = 103;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            LvcReference.MOD_ID, "textures/gui/gitmatica_widgets.png");

    private final int u;
    private final int v;
    private final int width;
    private final int height;

    GitmaticaIcons(int u, int v, int width, int height)
    {
        this.u = u;
        this.v = v;
        this.width = width;
        this.height = height;
    }

    @Override
    public int getWidth()
    {
        return this.width;
    }

    @Override
    public int getHeight()
    {
        return this.height;
    }

    @Override
    public int getU()
    {
        return this.u;
    }

    @Override
    public int getV()
    {
        return this.v;
    }

    @Override
    public Identifier getTexture()
    {
        return TEXTURE;
    }

    @Override
    public void renderAt(GuiContext context, int x, int y, float zLevel, boolean enabled, boolean selected)
    {
        this.renderScaledAt(context, x, y, this.width, this.height);
    }

    public void renderScaledAt(GuiContext context, int x, int y, int width, int height)
    {
        Pair<GpuTextureView, GpuSampler> texture = context.bindTexture(TEXTURE);

        if (texture == null)
        {
            return;
        }

        float uScale = 1.0F / TEXTURE_WIDTH;
        float vScale = 1.0F / TEXTURE_HEIGHT;
        context.addSimpleElement(new MaLiLibTexturedGuiElement(
                RenderPipelines.GUI_TEXTURED,
                context.setupTexture(texture),
                new Matrix3x2f(context.pose()),
                x,
                y,
                x + width,
                y + height,
                this.u * uScale,
                (this.u + this.width) * uScale,
                this.v * vScale,
                (this.v + this.height) * vScale,
                -1,
                context.peekLastScissor()));
    }
}
