package gale.event.impl;

import gale.event.Event;
import net.minecraft.entity.Entity;

/** An entity appears in the client world (you too, on every world load). Game thread. */
public final class EntityJoinEvent extends Event {
    public final Entity entity;

    public EntityJoinEvent(Entity entity) { this.entity = entity; }
}
