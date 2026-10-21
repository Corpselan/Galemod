package gale.event;

import java.util.function.Consumer;

/** One subscription on the {@link EventBus}: an event type, a priority, and what to do. */
public final class Listener<E> {
    final Class<E> type;
    final int priority;
    final Consumer<? super E> action;
    final String owner;
    final long order;

    Listener(Class<E> type, int priority, Consumer<? super E> action, String owner, long order) {
        this.type = type;
        this.priority = priority;
        this.action = action;
        this.owner = owner;
        this.order = order;
    }

    public Class<E> type() { return type; }

    public int priority() { return priority; }

    public String owner() { return owner; }
}
