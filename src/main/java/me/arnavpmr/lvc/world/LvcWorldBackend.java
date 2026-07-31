package me.arnavpmr.lvc.world;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.EntityDataManager;
import me.arnavpmr.lvc.capture.LvcBlockStateOnlyWorldReader;
import me.arnavpmr.lvc.capture.LvcMinecraftWorldReader;
import me.arnavpmr.lvc.capture.LvcServuxWorldReader;
import me.arnavpmr.lvc.capture.LvcWorldReader;

public enum LvcWorldBackend
{
    DIRECT("direct", true, true, false),
    SERVUX("servux", true, true, false),
    COMMANDS("commands", false, false, true);

    private final String id;
    private final boolean blockEntities;
    private final boolean entities;
    private final boolean lossy;

    LvcWorldBackend(String id, boolean blockEntities, boolean entities, boolean lossy)
    {
        this.id = id;
        this.blockEntities = blockEntities;
        this.entities = entities;
        this.lossy = lossy;
    }

    public static LvcWorldBackend resolve(Level world)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (world instanceof ServerLevel || minecraft.hasSingleplayerServer())
        {
            return DIRECT;
        }

        if (EntityDataManager.getInstance().hasServuxServer() &&
                Configs.Generic.PASTE_USING_SERVUX.getBooleanValue())
        {
            return SERVUX;
        }

        return COMMANDS;
    }

    public LvcWorldReader createReader(Level world)
    {
        return this.createReader(world, false);
    }

    public LvcWorldReader createReader(Level world, boolean requireFreshServuxBulkReply)
    {
        return switch (this)
        {
            case DIRECT -> new LvcMinecraftWorldReader(world);
            case SERVUX -> new LvcServuxWorldReader(world, requireFreshServuxBulkReply);
            case COMMANDS -> new LvcBlockStateOnlyWorldReader(world);
        };
    }

    public boolean capturesBlockEntities()
    {
        return this.blockEntities;
    }

    public boolean capturesEntities()
    {
        return this.entities;
    }

    public boolean lossy()
    {
        return this.lossy;
    }

    public String id()
    {
        return this.id;
    }
}
