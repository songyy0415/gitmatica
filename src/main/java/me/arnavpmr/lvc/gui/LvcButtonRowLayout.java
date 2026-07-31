package me.arnavpmr.lvc.gui;

import java.util.function.ToIntFunction;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

final class LvcButtonRowLayout
{
    private final int y;
    private final int height;
    private final int gap;
    private final int minimumWidth;
    private final int textPadding;
    private final ToIntFunction<String> textWidth;
    private int nextX;

    LvcButtonRowLayout(int x, int y, int height, int gap, int minimumWidth, int textPadding,
                       ToIntFunction<String> textWidth)
    {
        this.nextX = x;
        this.y = y;
        this.height = height;
        this.gap = gap;
        this.minimumWidth = minimumWidth;
        this.textPadding = textPadding;
        this.textWidth = textWidth;
    }

    ButtonGeneric next(String label)
    {
        int width = Math.max(this.minimumWidth, this.textWidth.applyAsInt(label) + this.textPadding);
        ButtonGeneric button = new ButtonGeneric(this.nextX, this.y, width, this.height, label);
        this.nextX += width + this.gap;
        return button;
    }
}
