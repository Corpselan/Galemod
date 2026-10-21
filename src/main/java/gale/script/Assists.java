package gale.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The areas of the script API ("assists": packets, combat, data...), each switched on and off by a module in
 * ClickGUI's Assist category. A switched-off area's functions do nothing (the script gets null and a note in chat)
 * and its events stop firing. A part ("system.files") works only while it and its area are on. "core" (version,
 * help, on, off) is always on.
 */
public final class Assists {
    private static final Map<String, BooleanSupplier> SWITCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Long> USED = new ConcurrentHashMap<>();

    private Assists() { }

    /** @param where what switches it, for messages: "Script Packets", "Script System → Files" */
    public static void bind(String area, String where, BooleanSupplier on) {
        SWITCHES.put(area, on);
        NAMES.put(area, where);
    }

    /** Whether the area and every area above it ("system" for "system.files") are on. */
    public static boolean on(String area) {
        String a = area;
        while (true) {
            BooleanSupplier s = SWITCHES.get(a);
            if (s != null && !s.getAsBoolean()) return false;
            int dot = a.lastIndexOf('.');
            if (dot < 0) return true;
            a = a.substring(0, dot);
        }
    }

    /** The switch that turned the area off, for the message to the script ("ClickGUI → 辅助 → Script System → Files"). */
    public static String offSwitch(String area) {
        String a = area, found = null;
        while (true) {
            BooleanSupplier s = SWITCHES.get(a);
            if (s != null && !s.getAsBoolean()) found = NAMES.get(a);
            int dot = a.lastIndexOf('.');
            if (dot < 0) break;
            a = a.substring(0, dot);
        }
        return "ClickGUI → 辅助 → " + (found == null ? area : found);
    }

    static void used(String area) { USED.put(area, System.currentTimeMillis()); }

    /** When a script last called a function of the area or its parts (ms), 0 for never. */
    public static long lastUsed(String area) {
        long last = 0;
        for (Map.Entry<String, Long> e : USED.entrySet()) if (within(e.getKey(), area)) last = Math.max(last, e.getValue());
        return last;
    }

    static boolean within(String area, String parent) { return area.equals(parent) || area.startsWith(parent + "."); }

    /** A short note for the settings screen: which scripts listen to the area's events, or that it was just used. */
    public static String status(String area) {
        List<String> owners = new ArrayList<>();
        for (ScriptEvents.Kind k : ScriptEvents.all()) {
            if (!within(k.area, area)) continue;
            for (String o : k.owners()) if (!owners.contains(o)) owners.add(o);
        }
        if (owners.size() == 1) return owners.get(0) + " 在用";
        if (owners.size() > 1) return owners.get(0) + " 等 " + owners.size() + " 个脚本在用";
        long t = lastUsed(area);
        return t > 0 && System.currentTimeMillis() - t < 10_000 ? "刚有脚本用过" : null;
    }
}
