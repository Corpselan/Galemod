package gale.script;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which class is which B4 script. B4 compiles each script into a class whose name is only I's and l's; when a script
 * registers a Gale event under its name, its class is on the call stack, so Gale learns the pair (the profiler uses
 * it to name scripts).
 */
public final class ScriptNames {
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    private ScriptNames() { }

    /** Whether the (outer) class name looks like a B4 script class: 20 or more characters, all I or l. */
    public static boolean isScript(String outerClass) {
        if (outerClass.length() < 20) return false;
        for (int i = 0; i < outerClass.length(); i++) {
            char c = outerClass.charAt(i);
            if (c != 'I' && c != 'l') return false;
        }
        return true;
    }

    /** The outer class of a class name (before the first '$'). */
    public static String outer(String className) {
        int d = className.indexOf('$');
        return d < 0 ? className : className.substring(0, d);
    }

    /** The script's name for its class, or null when it has not told Gale. */
    public static String of(String scriptClass) { return NAMES.get(scriptClass); }

    /** Called while a script calls into Gale: the script class on the stack gets this name. */
    static void remember(String name) {
        Class<?>[] stack = new Stack().get();
        if (stack == null) return;
        for (Class<?> c : stack) {
            String outer = outer(c.getName());
            if (isScript(outer)) {
                NAMES.put(outer, name);
                return;
            }
        }
    }

    private static final class Stack extends SecurityManager {
        Class<?>[] get() { return getClassContext(); }
    }
}
