package gale.meowtils.api.event.api;

import gale.Gale;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Meowtils' event bus, as extensions use it: @EventTarget methods with one Event parameter, by priority; a cancel stops it. */
public final class EventManager {
    private static final EnumMap<EventPriority, List<Listener>> LISTENERS = new EnumMap<>(EventPriority.class);
    private static final Set<String> REPORTED = new HashSet<>();

    static {
        for (EventPriority p : EventPriority.values()) LISTENERS.put(p, new CopyOnWriteArrayList<>());
    }

    private EventManager() { }

    public static void register(Object listener) {
        for (Class<?> c = listener.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                EventTarget target = m.getAnnotation(EventTarget.class);
                if (target == null || m.getParameterTypes().length != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                List<Listener> list = LISTENERS.get(target.priority());
                boolean known = false;
                for (Listener l : list) if (l.owner == listener && l.method.equals(m)) known = true;
                if (known) continue;
                m.setAccessible(true);
                list.add(new Listener(listener, m));
            }
        }
    }

    public static void unregister(Object listener) {
        for (List<Listener> list : LISTENERS.values()) list.removeIf(l -> l.owner == listener);
    }

    /** Whether anything listens for this kind of event (Gale only builds events someone wants). */
    public static boolean wants(Class<? extends Event> type) {
        for (List<Listener> list : LISTENERS.values()) for (Listener l : list) if (l.type.isAssignableFrom(type)) return true;
        return false;
    }

    public static void post(Event event) {
        for (EventPriority p : EventPriority.values()) {
            for (Listener l : LISTENERS.get(p)) {
                if (!l.type.isAssignableFrom(event.getClass())) continue;
                try {
                    l.method.invoke(l.owner, event);
                } catch (Throwable t) {
                    Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                    if (REPORTED.add(l.owner.getClass().getName() + "#" + l.method.getName() + ":" + cause.getClass().getName()))
                        Gale.LOG.error("An extension's {} listener ({}) threw", event.getClass().getSimpleName(), l.owner.getClass().getName(), cause);
                }
                if (event.isCancelled()) return;
            }
        }
    }

    /** Everything an extension's class loader registered (when extensions are unloaded). */
    public static void unregisterAll(ClassLoader loader) {
        for (List<Listener> list : LISTENERS.values()) list.removeIf(l -> l.owner.getClass().getClassLoader() == loader);
    }

    public static List<Object> listeners() {
        List<Object> out = new ArrayList<>();
        for (List<Listener> list : LISTENERS.values()) for (Listener l : list) if (!out.contains(l.owner)) out.add(l.owner);
        return out;
    }

    private static final class Listener {
        final Object owner;
        final Method method;
        final Class<?> type;

        Listener(Object owner, Method method) {
            this.owner = owner;
            this.method = method;
            this.type = method.getParameterTypes()[0];
        }
    }
}
