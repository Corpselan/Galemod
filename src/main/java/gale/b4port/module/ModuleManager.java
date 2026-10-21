package gale.b4port.module;

import gale.b4port.module.setting.Setting;
import gale.bridge.B4State;
import gale.bridge.External;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B4's module lookup for the ported modules. A name gives Gale's port of that module; while it gives way to B4's own
 * module (B4 runs it), a stand-in for B4's that answers from what GaleLink reports (targets, Scaffold / Tower placing),
 * so the other ported modules see what B4's module does. B4 modules Gale has no port of are not there (null).
 */
public final class ModuleManager {
    private static final Map<String, Module> PORTS = new ConcurrentHashMap<>();
    private static final Map<String, B4Side> SIDES = new ConcurrentHashMap<>();
    public static final List<Module> afterDisableQueue = new ArrayList<>();

    private ModuleManager() { }

    public static void add(Module m) {
        PORTS.put(m.getName(), m);
    }

    public static java.util.Collection<Module> ports() {
        return PORTS.values();
    }

    /** The module: Gale's port, or B4's while Gale's gives way; null if Gale has no port of it. */
    public static Module get(String name) {
        Module m = PORTS.get(name);
        if (m == null) return null;
        return m.isDeferred() ? SIDES.computeIfAbsent(name, n -> new B4Side(n)) : m;
    }

    /** The module if it is on (Gale's port running, or B4's on), else null. */
    public static Module enabled(String name) {
        Module m = get(name);
        if (m == null) return null;
        if (m instanceof B4Side) return External.isEnabled("b4", name) ? m : null;
        return m.isEnabled() ? m : null;
    }

    /** Whether a module from {@link #get} is on (for B4's stand-in: on in B4). */
    public static boolean isOn(Module m) {
        if (m == null) return false;
        if (m instanceof B4Side) return External.isEnabled("b4", m.getName());
        return m.isEnabled();
    }

    public static Setting setting(Module m, String name) {
        if (m instanceof B4Side) {
            // B4's values are not known: Gale's port of the module has the same settings
            Module port = PORTS.get(m.getName());
            if (port != null) m = port;
        }
        for (Setting s : m.getB4Settings()) {
            if (s.getName().equals(name)) return s;
        }
        throw new IllegalStateException(m.getName() + " has no setting " + name);
    }

    /** B4's module of a name, as far as GaleLink tells: on or off, its target, whether it is placing. */
    static final class B4Side extends Module {
        B4Side(String name) {
            super(name);
        }

        @Override
        public Entity getTarget() {
            switch (getName()) {
                case "KillAura": return B4State.killAuraTarget();
                case "AimAssist": return B4State.aimAssistTarget();
                case "Backtrack": return B4State.backtrackTarget();
                case "Lag Range": return B4State.lagRangeTarget();
                default: return null;
            }
        }

        @Override
        public boolean isScaffolding() {
            return getName().equals("Scaffold") && B4State.scaffolding();
        }

        @Override
        public boolean isTowering() {
            return getName().equals("Tower") && B4State.towering();
        }
    }
}
