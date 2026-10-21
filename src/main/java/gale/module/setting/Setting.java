package gale.module.setting;

import com.google.gson.JsonElement;
import gale.util.GuiVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A module setting: a name, a value and its default. Made with the factories in Module (bool, number, range, mode,
 * color, text), shown in the GUI and the {@code /gale set} command, and saved in the config.
 */
public abstract class Setting<T> {
    private final String name;
    private String description = "";
    protected T value;
    protected final T defaultValue;
    private BooleanSupplier visible = () -> true;
    private int hiddenFrom;
    private final List<Consumer<T>> changeListeners = new ArrayList<>();
    private Runnable onAnyChange;

    protected Setting(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S describe(String description) {
        this.description = description;
        return (S) this;
    }

    public T getValue() { return value; }

    public T getDefault() { return defaultValue; }

    /** Sets the value (kept within the setting's limits). Calls the change listeners when it really changed. */
    public void setValue(T value) {
        T fixed = fix(value);
        if (fixed == null || fixed.equals(this.value)) return;
        this.value = fixed;
        fireChanged();
    }

    /** Tells the change listeners (and the config) that the setting changed. */
    protected void fireChanged() {
        for (Consumer<T> c : changeListeners) c.accept(value);
        if (onAnyChange != null) onAnyChange.run();
    }

    public void reset() { setValue(defaultValue); }

    /** Only shown (in the GUI and lists) while this is true, e.g. {@code visibleWhen(() -> mode.is("Blatant"))}. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier visible) {
        this.visible = visible;
        return (S) this;
    }

    /** GUI-only hiding for this one option; its value, config and command access remain available. */
    public boolean isHide() { return GuiVisibility.hideFrom(hiddenFrom); }

    /** Hide one option at declaration time, e.g. {@code bool("Internal", true).isHide(true)}. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S isHide(boolean hidden) {
        return hideFrom(hidden ? 2 : 0);
    }

    /** GUI-only rule: hide from edition #2 or #3; 0 removes the rule. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S hideFrom(int level) {
        this.hiddenFrom = GuiVisibility.checkLevel(level);
        return (S) this;
    }

    public boolean isVisible() { return !GuiVisibility.isHidden(isHide()) && visible.getAsBoolean(); }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Consumer<T> listener) {
        changeListeners.add(listener);
        return (S) this;
    }

    /** Set by the module so any change marks the config to be saved. */
    public void setOnAnyChange(Runnable r) { this.onAnyChange = r; }

    /** The value made valid (clamped, snapped, a known option); null when it cannot be. */
    protected T fix(T value) { return value; }

    /** Reads a value typed in a command; false when the text does not fit this setting. */
    public abstract boolean parse(String text);

    /** The value as shown to people. */
    public abstract String display();

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);

    @Override
    public String toString() { return name + "=" + display(); }
}
