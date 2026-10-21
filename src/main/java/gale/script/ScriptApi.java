package gale.script;

import gale.Gale;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What B4 scripts get from {@code bridge.get("gale")}. It is a {@link Map} because a Map is something a B4 script can
 * use without any import B4 forbids; the map calls Gale's {@link Functions}:
 * <pre>
 * Map&lt;String, Object&gt; gale = (Map&lt;String, Object&gt;) bridge.get("gale");
 * Object r = gale.get("name arg1 arg2");                    // arguments as text
 * Object r = gale.put("name", new Object[]{ arg1, arg2 });  // arguments as objects (B4 Entity, Vec3, lambdas...)
 * gale.containsKey("name");                                 // whether this Gale has that function
 * gale.keySet();                                            // every function's name
 * </pre>
 * The return value is what the function gives back (B4 Entity / Vec3 / ItemStack / packets for game objects), null when
 * it gives nothing or something went wrong (the problem is shown in chat).
 */
public final class ScriptApi extends AbstractMap<String, Object> {
    public static final ScriptApi INSTANCE = new ScriptApi();

    private ScriptApi() { }

    @Override
    public Object get(Object key) {
        if (key == null) return null;
        String[] parts = key.toString().trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return Functions.call(parts[0], args);
    }

    @Override
    public Object put(String key, Object value) {
        if (key == null) return null;
        Object[] args = value == null ? new Object[0] : value instanceof Object[] ? (Object[]) value : new Object[]{ value };
        return Functions.call(key.trim(), args);
    }

    @Override
    public boolean containsKey(Object key) { return key != null && Functions.has(key.toString().trim()); }

    @Override
    public Object remove(Object key) { return null; }

    @Override
    public void clear() { }

    @Override
    public int size() { return Functions.all().size(); }

    @Override
    public Set<String> keySet() {
        Set<String> out = new LinkedHashSet<>();
        for (Functions.Entry e : Functions.all()) out.add(e.name);
        return out;
    }

    /** name -> usage, for listing. */
    @Override
    public Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> out = new LinkedHashSet<>();
        for (Functions.Entry e : Functions.all()) out.add(new SimpleImmutableEntry<>(e.name, e.usage));
        return out;
    }

    @Override
    public boolean equals(Object o) { return o == this; }

    @Override
    public int hashCode() { return System.identityHashCode(this); }

    @Override
    public String toString() { return "Gale " + Gale.VERSION + " (" + Functions.all().size() + " functions, gale.get(\"help\"))"; }
}
