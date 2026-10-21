package gale.event.impl;

import gale.event.CancellableEvent;
import net.minecraft.entity.Entity;

/**
 * An entity status packet (S19, "entity event") is being applied. <b>Game thread.</b> Cancel to drop it.
 * Common statuses: 2 hurt, 3 death, 9 finished using an item, 18 love hearts, 31 fishing hook pull...
 * <p>{@code entity} is null when the entity is not in your world.
 */
public final class EntityStatusEvent extends CancellableEvent {
    public final int entityId;
    public final byte status;
    public final Entity entity;

    public EntityStatusEvent(int entityId, byte status, Entity entity) {
        this.entityId = entityId;
        this.status = status;
        this.entity = entity;
    }
}
