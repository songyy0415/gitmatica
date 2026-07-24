package me.niicide.lvc.gui;

import net.minecraft.resources.Identifier;

import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import me.niicide.lvc.LvcReference;

public enum GitmaticaButtonIcons implements IGuiIcon
{
    FOLDER(0, 0, 14, 14);

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            LvcReference.MOD_ID, "textures/gui/gitmatica_buttons.png");

    private final int u;
    private final int v;
    private final int width;
    private final int height;

    GitmaticaButtonIcons(int u, int v, int width, int height)
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
        int u = this.u;

        if (enabled)
        {
            u += this.width;
        }

        if (selected)
        {
            u += this.width;
        }

        RenderUtils.drawTexturedRect(
                context,
                TEXTURE,
                x,
                y,
                u,
                this.v,
                this.width,
                this.height,
                zLevel);
    }
}
