package gale.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The functions B4 scripts can call through {@code gale}. Each part of Gale's script API registers its own (see
 * gale.script.api). Names are case-insensitive, written like {@code "attackLocal"} or {@code "packet.send"}.
 */
public final class Functions {
    /** A function: gets the call's arguments, returns what the script gets back (game objects are wrapped for B4). */
    public interface Fn {
        Object call(Args a) throws Exception;
    }

    public static final class Entry {
        /** area: the assist it belongs to ("packets", "system.files"...), see {@link Assists} */
        public final String area, name, usage, doc;
        final Fn fn;

        Entry(String area, String name, String usage, String doc, Fn fn) {
            this.area = area;
            this.name = name;
            this.usage = usage;
            this.doc = doc;
            this.fn = fn;
        }
    }

    private static final Map<String, Entry> ALL = Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

    private Functions() { }

    /**
     * @param usage the arguments, e.g. {@code "<实体> [减速=0.6] [保持疾跑=false]"}
     * @param doc   one or two sentences for {@code gale.get("help name")} and the docs
     */
    public static void add(String area, String name, String usage, String doc, Fn fn) { ALL.put(name, new Entry(area, name, usage, doc, fn)); }

    /** A function of the always-on core. */
    public static void add(String name, String usage, String doc, Fn fn) { add("core", name, usage, doc, fn); }

    public static boolean has(String name) { return ALL.containsKey(name); }

    public static Entry get(String name) { return ALL.get(name); }

    public static List<Entry> all() {
        synchronized (ALL) {
            return new ArrayList<>(ALL.values());
        }
    }

    /** Runs a function for a script; problems go to chat and give null. */
    static Object call(String name, Object[] args) {
        Entry e = ALL.get(name);
        if (e == null) {
            ScriptErrors.report("gale." + name, "Gale 没有这个函数（gale.get(\"help\") 看全部）", null);
            return null;
        }
        if (!Assists.on(e.area)) {
            ScriptErrors.report("gale." + e.name, "这个辅助关着（" + Assists.offSwitch(e.area) + "）", null);
            return null;
        }
        Assists.used(e.area);
        try {
            return B4Link.wrap(e.fn.call(new Args(e.name, args)));
        } catch (Throwable t) {
            ScriptErrors.report("gale." + e.name, null, t);
            return null;
        }
    }
}
