package gale.event.impl;

import gale.event.Event;

/** Every client tick (20 a second), in menus too. Game thread. */
public abstract class TickEvent extends Event {
    /** Start of the tick, before keys and clicks are handled and before anything moves. */
    public static final class Pre extends TickEvent {
    }

    /** End of the tick: everything has moved and the movement packet has been sent. */
    public static final class Post extends TickEvent {
    }
}
