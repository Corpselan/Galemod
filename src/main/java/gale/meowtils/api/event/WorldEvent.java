package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.multiplayer.WorldClient;

public class WorldEvent extends Event {
    private final WorldClient world;
    private final Type type;

    public WorldEvent(WorldClient world, Type type) {
        this.world = world;
        this.type = type;
    }

    public WorldClient getWorld() { return world; }

    public Type getType() { return type; }

    public enum Type { LOAD, UNLOAD }
}
