package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

public class EntityJoinWorldEvent extends Event {
    private final Entity entity;
    private final World world;

    public EntityJoinWorldEvent(Entity entity, World world) {
        this.entity = entity;
        this.world = world;
    }

    public Entity getEntity() { return entity; }

    public World getWorld() { return world; }
}
