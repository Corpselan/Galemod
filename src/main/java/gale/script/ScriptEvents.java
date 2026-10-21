package gale.script;

import gale.Gale;
import gale.event.Listener;
import gale.util.ChatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Events B4 scripts can listen to. A script registers a lambda:
 * <pre>
 * gale.put("on", new Object[]{ "attack", getScriptName(), (Comparator&lt;Object&gt;) (event, a) -&gt; {
 *     Object[] args = (Object[]) a;   // the event's values; change them to change the event
 *     return 0;                       // not 0: cancel (for events that can be cancelled)
 * } });
 * gale.put("off", new Object[]{ "attack", getScriptName() });   // or { getScriptName() } for all of the script's
 * </pre>
 * The second value names the owner: registering again under the same name replaces the old lambda (so reloading a
 * script does not leave its old one behind). Lambdas run on the thread the event happens on (see each event).
 * A lambda that throws is reported in chat; after 10 errors in a row it is removed.
 * <p>Gale only hooks the game for an event while some script listens to it.
 */
public final class ScriptEvents {
    public static final class Kind {
        /** area: the assist it belongs to, see {@link Assists} */
        public final String area, name, args, doc;
        final Listener<?> listener;
        final List<Handler> handlers = new CopyOnWriteArrayList<>();

        Kind(String area, String name, String args, String doc, Listener<?> listener) {
            this.area = area;
            this.name = name;
            this.args = args;
            this.doc = doc;
            this.listener = listener;
        }

        public int handlerCount() { return handlers.size(); }

        public List<String> owners() {
            List<String> out = new ArrayList<>();
            for (Handler h : handlers) out.add(h.owner);
            return out;
        }
    }

    static final class Handler {
        final String owner;
        final Comparator<Object> callback;
        int errors;

        Handler(String owner, Comparator<Object> callback) {
            this.owner = owner;
            this.callback = callback;
        }
    }

    private static final Map<String, Kind> KINDS = Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

    private ScriptEvents() { }

    /**
     * Declares an event for scripts, fed by one of Gale's own events: {@code onEvent} builds the values, calls
     * {@link #fire} and applies what came back. Its listener is only on Gale's bus while scripts listen.
     */
    public static <E> void define(String area, String name, String args, String doc, Class<E> busEvent, int priority, Consumer<E> onEvent) {
        Listener<E> l = busEvent == null ? null : Gale.BUS.listener(busEvent, priority, onEvent, "scripts:" + name);
        KINDS.put(name, new Kind(area, name, args, doc, l));
    }

    /** Declares an event that Gale fires itself with {@link #fire} (no Gale event behind it). */
    public static void define(String area, String name, String args, String doc) { KINDS.put(name, new Kind(area, name, args, doc, null)); }

    public static List<Kind> all() {
        synchronized (KINDS) {
            return new ArrayList<>(KINDS.values());
        }
    }

    public static Kind kind(String name) { return KINDS.get(name); }

    /** Whether a script listens to this event and its assist is on. */
    public static boolean has(String name) {
        Kind k = KINDS.get(name);
        return k != null && !k.handlers.isEmpty() && Assists.on(k.area);
    }

    public static synchronized void on(String event, String owner, Comparator<Object> callback) {
        Kind k = KINDS.get(event);
        if (k == null) throw new IllegalArgumentException("没有事件 \"" + event + "\"（gale.get(\"events\") 看全部）");
        ScriptNames.remember(owner);
        boolean first = k.handlers.isEmpty();
        k.handlers.removeIf(h -> h.owner.equals(owner));
        k.handlers.add(new Handler(owner, callback));
        if (first && k.listener != null) Gale.BUS.register(k.listener);
    }

    /** Removes the owner's lambda from one event, or from all events when event is null. How many were removed. */
    public static synchronized int off(String event, String owner) {
        int removed = 0;
        for (Kind k : all()) {
            if (event != null && !k.name.equalsIgnoreCase(event)) continue;
            int before = k.handlers.size();
            k.handlers.removeIf(h -> h.owner.equals(owner));
            removed += before - k.handlers.size();
            if (k.handlers.isEmpty() && k.listener != null) Gale.BUS.unregister(k.listener);
        }
        return removed;
    }

    /**
     * Calls every lambda of the event in the order they were registered, each seeing the values as the ones before left
     * them. True when one of them returned non-zero (cancel).
     */
    public static boolean fire(String event, Object[] args) {
        Kind k = KINDS.get(event);
        if (k == null || k.handlers.isEmpty() || !Assists.on(k.area)) return false;
        boolean cancel = false;
        for (Handler h : k.handlers) {
            try {
                if (h.callback.compare(k.name, args) != 0) cancel = true;
                h.errors = 0;
            } catch (Throwable t) {
                ScriptErrors.report(h.owner + " 的 " + k.name + " 回调", null, t);
                if (++h.errors >= 10) {
                    off(k.name, h.owner);
                    ChatUtil.warn(h.owner + " 的 " + k.name + " 回调连续出错 10 次，已移除");
                }
            }
        }
        return cancel;
    }

    // ------------------------------------------------------------------ helpers for reading back what scripts changed

    public static double num(Object[] a, int i, double fallback) {
        return i < a.length && a[i] instanceof Number ? ((Number) a[i]).doubleValue() : fallback;
    }

    public static boolean bool(Object[] a, int i, boolean fallback) {
        return i < a.length && a[i] instanceof Boolean ? (Boolean) a[i] : fallback;
    }
}
