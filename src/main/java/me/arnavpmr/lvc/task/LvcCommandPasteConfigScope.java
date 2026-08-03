package me.arnavpmr.lvc.task;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.util.PasteNbtBehavior;
import fi.dy.masa.litematica.util.ReplaceBehavior;

final class LvcCommandPasteConfigScope implements AutoCloseable
{
    private final IConfigOptionListEntry replaceBehavior;
    private final IConfigOptionListEntry layerBehavior;
    private final IConfigOptionListEntry nbtBehavior;
    private final boolean ignoreEntities;
    private boolean closed;

    private LvcCommandPasteConfigScope()
    {
        this.replaceBehavior = Configs.Generic.PASTE_REPLACE_BEHAVIOR.getOptionListValue();
        this.layerBehavior = Configs.Generic.PASTE_LAYER_BEHAVIOR.getOptionListValue();
        this.nbtBehavior = Configs.Generic.PASTE_NBT_BEHAVIOR.getOptionListValue();
        this.ignoreEntities = Configs.Generic.PASTE_IGNORE_ENTITIES.getBooleanValue();
    }

    static LvcCommandPasteConfigScope apply()
    {
        LvcCommandPasteConfigScope scope = new LvcCommandPasteConfigScope();
        Configs.Generic.PASTE_REPLACE_BEHAVIOR.setOptionListValue(ReplaceBehavior.ALL);
        Configs.Generic.PASTE_LAYER_BEHAVIOR.setOptionListValue(PasteLayerBehavior.ALL);
        Configs.Generic.PASTE_NBT_BEHAVIOR.setOptionListValue(PasteNbtBehavior.NONE);
        Configs.Generic.PASTE_IGNORE_ENTITIES.setBooleanValue(true);
        return scope;
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        Configs.Generic.PASTE_REPLACE_BEHAVIOR.setOptionListValue(this.replaceBehavior);
        Configs.Generic.PASTE_LAYER_BEHAVIOR.setOptionListValue(this.layerBehavior);
        Configs.Generic.PASTE_NBT_BEHAVIOR.setOptionListValue(this.nbtBehavior);
        Configs.Generic.PASTE_IGNORE_ENTITIES.setBooleanValue(this.ignoreEntities);
        this.closed = true;
    }
}
