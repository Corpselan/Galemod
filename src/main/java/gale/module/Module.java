package gale.module;

import gale.Gale;
import gale.util.GuiVisibility;
import gale.bridge.External;
import gale.core.Notifications;
import gale.event.Listener;
import gale.event.Priority;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.RangeSetting;
import gale.module.setting.Setting;
import gale.module.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A feature that can be switched on and off. Subclasses declare settings and event listeners in their constructor:
 *
 * <pre>
 * public final class Example extends Module {
 *     private final NumberSetting range = number("Range", 3.0, 1.0, 6.0, 0.1);
 *
 *     public Example() {
 *         super("Example", Category.COMBAT, "what it does");
 *         on(UpdateEvent.class, e -> { ... });
 *     }
 * }
 * </pre>
 *
 * Listeners added with {@link #on} only run while the module is <i>active</i>: on, in a world, and not deferring to
 * another client (see {@link #complements}).
 */
public abstract class Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    /** What the bound key does: switch on and off, or keep the module on only while the key is held. */
    public enum BindMode { TOGGLE, HOLD }

    private final String name, description;
    private final Category category;
    private boolean enabled, hidden, alwaysOn;
    private int key;
    private int hiddenFrom;
    private BindMode bindMode = BindMode.TOGGLE;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Listener<?>> listeners = new ArrayList<>();
    private String[] complements = new String[0];
    private BoolSetting defer;

    protected Module(String name, Category category, String description) {
        this.name = name;
        this.category = category;
        this.description = description == null ? "" : description;
    }

    // ------------------------------------------------------------------ info

    public final String getName() { return name; }

    public final Category getCategory() { return category; }

    public final String getDescription() { return description; }

    public final int getKey() { return key; }

    public final void setKey(int key) {
        this.key = key;
        Gale.config().markDirty();
    }

    public final BindMode getBindMode() { return bindMode; }

    public final void setBindMode(BindMode mode) {
        this.bindMode = mode == null ? BindMode.TOGGLE : mode;
        Gale.config().markDirty();
    }

    /** Hidden modules are left out of the HUD list. */
    public final boolean isHidden() { return hidden; }

    /** GUI-only hiding. Existing overrides returning true are revealed in edition #1 as well. */
    public boolean isHide() { return GuiVisibility.hideFrom(hiddenFrom); }

    /** Use in a module constructor: hideFrom(2) or hideFrom(3). 0 removes the rule. */
    public final Module hideFrom(int level) {
        this.hiddenFrom = GuiVisibility.checkLevel(level);
        return this;
    }

    public final Module isHide(boolean hidden) { return hideFrom(hidden ? 2 : 0); }

    public final boolean isGuiVisible() { return !GuiVisibility.isHidden(isHide()); }

    public final void setHidden(boolean hidden) {
        this.hidden = hidden;
        Gale.config().markDirty();
    }

    public final List<Setting<?>> getSettings() { return Collections.unmodifiableList(settings); }

    /** A setting by name, ignoring case and spaces; null if there is none. */
    public final Setting<?> getSetting(String name) {
        String want = ModuleManager.key(name);
        for (Setting<?> s : settings) if (ModuleManager.key(s.getName()).equals(want)) return s;
        return null;
    }

    /** Extra text the HUD shows after the name (a mode, a number); null for none. */
    public String getSuffix() { return null; }

    /** A short live note the settings screen shows next to the name ("LeaderCombat 在用"); null for none. */
    public String getInfo() { return null; }

    /** The other clients' module names this one stands in for (see {@link #complements}). */
    public final String[] getComplements() { return complements.clone(); }

    // ------------------------------------------------------------------ state

    public final boolean isEnabled() { return enabled; }

    /** Modules that are always on hold settings for the rest of Gale (Targets, Rotations); they cannot be switched off. */
    public final boolean isAlwaysOn() { return alwaysOn; }

    /** On, in a world, and not deferring to another client. Listeners from {@link #on} only run while this is true. */
    public final boolean isActive() {
        return enabled && mc.thePlayer != null && mc.theWorld != null && !isDeferred();
    }

    /** Whether another client reports one of the modules this one complements as on, and this module gives way. */
    public final boolean isDeferred() {
        return complements.length > 0 && defer != null && defer.get() && External.anyEnabled(complements);
    }

    public final void setEnabled(boolean on) {
        if (alwaysOn) on = true;
        if (on == enabled) return;
        enabled = on;
        for (Listener<?> l : listeners) {
            if (on) Gale.BUS.register(l);
            else Gale.BUS.unregister(l);
        }
        try {
            if (on) onEnable();
            else onDisable();
        } catch (Throwable t) {
            Gale.LOG.error("{} threw in {}", name, on ? "onEnable" : "onDisable", t);
        }
        Gale.config().markDirty();
        Notifications.toggled(this);
    }

    public final void toggle() { setEnabled(!enabled); }

    /** Called with the game outside or inside a world: check for a null player before using it. */
    protected void onEnable() { }

    protected void onDisable() { }

    /** The bound key went down: toggles, or switches on in HOLD mode. Override for another action (ClickGUI opens a screen). */
    protected void onKeyPressed() {
        if (alwaysOn) return;
        if (bindMode == BindMode.HOLD) setEnabled(true);
        else toggle();
    }

    /** The bound key came up: switches off in HOLD mode. */
    protected void onKeyReleased() {
        if (!alwaysOn && bindMode == BindMode.HOLD) setEnabled(false);
    }

    // ------------------------------------------------------------------ for subclasses

    protected static EntityPlayerSP player() { return mc.thePlayer; }

    protected static WorldClient world() { return mc.theWorld; }

    /** Listens while the module is active (on, in a world, not deferring). */
    protected final <E> void on(Class<E> type, Consumer<? super E> action) { on(type, Priority.NORMAL, action); }

    protected final <E> void on(Class<E> type, int priority, Consumer<? super E> action) {
        addListener(Gale.BUS.listener(type, priority, e -> {
            if (isActive()) action.accept(e);
        }, name));
    }

    /** Listens whenever the module is on, also outside a world and while deferring (check for a null player yourself). */
    protected final <E> void onAlways(Class<E> type, int priority, Consumer<? super E> action) {
        addListener(Gale.BUS.listener(type, priority, action, name));
    }

    private void addListener(Listener<?> l) {
        listeners.add(l);
        if (enabled) Gale.BUS.register(l);
    }

    /**
     * Says this module does the same job as these modules of another client (B4's "Velocity", "Sprint"...). When the
     * bridge reports one of them as on, this module pauses, so the two do not fight. Adds a "Defer" setting to turn that off.
     */
    protected final void complements(String... externalModules) {
        this.complements = externalModules.clone();
        if (defer == null) defer = bool("Defer", true).describe("另一个端的同类功能开着时，这个模块先停下");
    }

    /** Starts the module on, quietly (call first in the constructor; the saved config can still switch it off). */
    protected final void startOn() { this.enabled = true; }

    /** Makes the module always on (for modules that only hold settings). Call in the constructor. */
    protected final void alwaysOn() {
        this.alwaysOn = true;
        this.hidden = true;
        this.enabled = false;
    }

    protected final BoolSetting bool(String name, boolean def) { return add(new BoolSetting(name, def)); }

    protected final NumberSetting number(String name, double def, double min, double max, double step) {
        return add(new NumberSetting(name, def, min, max, step));
    }

    protected final RangeSetting range(String name, double low, double high, double min, double max, double step) {
        return add(new RangeSetting(name, low, high, min, max, step));
    }

    protected final ModeSetting mode(String name, String def, String... modes) { return add(new ModeSetting(name, def, modes)); }

    protected final ColorSetting color(String name, int argb) { return add(new ColorSetting(name, argb)); }

    protected final TextSetting text(String name, String def) { return add(new TextSetting(name, def)); }

    protected final <S extends Setting<?>> S add(S setting) {
        if (getSetting(setting.getName()) != null) throw new IllegalArgumentException(name + " already has a setting " + setting.getName());
        settings.add(setting);
        setting.setOnAnyChange(() -> Gale.config().markDirty());
        return setting;
    }

    @Override
    public String toString() { return name; }
}
