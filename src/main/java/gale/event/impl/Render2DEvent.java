package gale.event.impl;

import gale.event.Event;
import net.minecraft.client.gui.ScaledResolution;

/** Every frame in a world, after the vanilla HUD: draw on the screen here, in scaled GUI coordinates. Game thread. */
public final class Render2DEvent extends Event {
    public final float partialTicks;
    public final ScaledResolution resolution;

    public Render2DEvent(float partialTicks, ScaledResolution resolution) {
        this.partialTicks = partialTicks;
        this.resolution = resolution;
    }

    public int width() { return resolution.getScaledWidth(); }

    public int height() { return resolution.getScaledHeight(); }
}
