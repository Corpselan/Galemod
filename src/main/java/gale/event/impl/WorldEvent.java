package gale.event.impl;

import gale.event.Event;
import net.minecraft.client.multiplayer.WorldClient;

/** The client world is loaded (joining a server, changing dimension or lobby) or unloaded. Game thread. */
public abstract class WorldEvent extends Event {
    public final WorldClient world;

    WorldEvent(WorldClient world) { this.world = world; }

    public static final class Load extends WorldEvent {
        public Load(WorldClient world) { super(world); }
    }

    public static final class Unload extends WorldEvent {
        public Unload(WorldClient world) { super(world); }
    }
}
