package gale.event.impl;

import gale.event.Event;

/** Every frame in a world, after the world is drawn: draw boxes and lines in the world here (see Render3D). Game thread. */
public final class Render3DEvent extends Event {
    public final float partialTicks;

    public Render3DEvent(float partialTicks) { this.partialTicks = partialTicks; }
}
