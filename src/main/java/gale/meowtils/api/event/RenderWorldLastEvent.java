package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;


public class RenderWorldLastEvent extends Event {
    private final float partialTicks;

    public RenderWorldLastEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() { return partialTicks; }
}
