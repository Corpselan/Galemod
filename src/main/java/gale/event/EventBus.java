package gale.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Gale's own event bus. Listeners are plain lambdas, matched by the exact event class, called highest priority first
 * (same priority: in the order they were added). Safe to post from any thread: packet events come from the network
 * thread while everything else is on the game thread. A listener that throws is logged (once per owner and kind of
 * error) and the rest still run.
 */
public final class EventBus {
    private static final Logger LOG = LogManager.getLogger("Gale/Events");
    private static final Listener<?>[] NONE = new Listener<?>[0];
    private static final Comparator<Listener<?>> ORDER = (a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : Long.compare(a.order, b.order);

    private final Map<Class<?>, Listener<?>[]> listeners = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(), errors = new AtomicLong();
    private final Map<String, Boolean> reported = new ConcurrentHashMap<>();

    /** How many times a listener has thrown since the game started. */
    public long errorCount() { return errors.get(); }

    /** Makes a listener without adding it; {@link #register} and {@link #unregister} switch it on and off. */
    public <E> Listener<E> listener(Class<E> type, int priority, Consumer<? super E> action, String owner) {
        return new Listener<>(type, priority, action, owner == null ? "?" : owner, counter.incrementAndGet());
    }

    /** Makes a listener and adds it at once. */
    public <E> Listener<E> listen(Class<E> type, int priority, Consumer<? super E> action, String owner) {
        Listener<E> l = listener(type, priority, action, owner);
        register(l);
        return l;
    }

    public <E> Listener<E> listen(Class<E> type, Consumer<? super E> action) {
        return listen(type, Priority.NORMAL, action, null);
    }

    public synchronized void register(Listener<?> l) {
        Listener<?>[] old = listeners.getOrDefault(l.type, NONE);
        for (Listener<?> x : old) if (x == l) return;
        Listener<?>[] now = Arrays.copyOf(old, old.length + 1);
        now[old.length] = l;
        Arrays.sort(now, ORDER);
        listeners.put(l.type, now);
    }

    public synchronized void unregister(Listener<?> l) {
        Listener<?>[] old = listeners.get(l.type);
        if (old == null) return;
        List<Listener<?>> keep = new ArrayList<>(old.length);
        for (Listener<?> x : old) if (x != l) keep.add(x);
        if (keep.isEmpty()) listeners.remove(l.type);
        else listeners.put(l.type, keep.toArray(NONE));
    }

    /** Whether anyone listens for this kind of event (to skip building events nobody wants). */
    public boolean hasListeners(Class<?> type) {
        return listeners.containsKey(type);
    }

    /** Sends the event to its listeners and gives it back, so the caller can read what they changed. */
    @SuppressWarnings("unchecked")
    public <E> E post(E event) {
        Listener<?>[] ls = listeners.get(event.getClass());
        if (ls == null) return event;
        for (Listener<?> l : ls) {
            try {
                ((Consumer<Object>) l.action).accept(event);
            } catch (Throwable t) {
                errors.incrementAndGet();
                String key = l.owner + "|" + event.getClass().getName() + "|" + t.getClass().getName();
                if (reported.putIfAbsent(key, Boolean.TRUE) == null)
                    LOG.error("Listener of " + l.owner + " threw on " + event.getClass().getSimpleName() + " (said once)", t);
            }
        }
        return event;
    }
}
