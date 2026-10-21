package gale.event.impl;

import gale.event.Event;

/** Every frame, before the world is drawn (in menus too): the place for smooth camera movement. Game thread. */
public final class FrameEvent extends Event {
    public final float partialTicks;

    public FrameEvent(float partialTicks) { this.partialTicks = partialTicks; }
}
