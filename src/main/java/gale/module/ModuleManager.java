package gale.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * All modules: registered at start, then looked up by name or class. Script modules come and go while the game runs
 * (register / unregister on the game thread); other threads may read the lists at any time.
 */
public final class ModuleManager {
    private final List<Module> modules = new CopyOnWriteArrayList<>();
    private final Map<String, Module> byName = new ConcurrentHashMap<>();
    private final Map<Class<?>, Module> byClass = new ConcurrentHashMap<>();

    /** The lookup form of a name: lower case, without spaces, '_' or '-'. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    public void register(Module... list) {
        for (Module m : list) {
            String k = key(m.getName());
            if (byName.containsKey(k)) throw new IllegalArgumentException("two modules called " + m.getName());
            modules.add(m);
            byName.put(k, m);
            byClass.put(m.getClass(), m);
        }
    }

    /** Takes a module out again (a script reloaded or removed); switch it off first. */
    public void unregister(Module m) {
        modules.remove(m);
        byName.remove(key(m.getName()), m);
        byClass.remove(m.getClass(), m);
    }

    public List<Module> all() { return Collections.unmodifiableList(modules); }

    public List<Module> in(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.getCategory() == category) out.add(m);
        return out;
    }

    /** The GUI's list; hidden implementation modules remain available to configuration, commands and events. */
    public List<Module> visibleIn(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.getCategory() == category && m.isGuiVisible()) out.add(m);
        return out;
    }

    public List<Module> visible() {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.isGuiVisible()) out.add(m);
        return out;
    }

    public List<Module> enabled() {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.isEnabled()) out.add(m);
        return out;
    }

    /** A module by name, ignoring case, spaces, '_' and '-'; null if there is none. */
    public Module get(String name) { return name == null ? null : byName.get(key(name)); }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) { return (T) byClass.get(type); }

    /** Runs the key action of the modules bound to this key (called on key presses and releases outside screens). */
    public void onKey(int key, boolean pressed) {
        if (key == 0) return;
        for (Module m : modules) {
            if (m.getKey() != key) continue;
            if (pressed) m.onKeyPressed();
            else m.onKeyReleased();
        }
    }

    /** Every tick: a HOLD module whose key is no longer held goes off (also when the key came up inside a screen). */
    public void tick() {
        for (Module m : modules)
            if (m.isEnabled() && !m.isAlwaysOn() && m.getBindMode() == Module.BindMode.HOLD && m.getKey() != 0 && !gale.util.KeyUtil.isDown(m.getKey()))
                m.setEnabled(false);
    }
}
