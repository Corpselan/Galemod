package gale.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import gale.util.GuiVisibility;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;

/** One of a fixed list of options. */
public final class ModeSetting extends Setting<String> {
    private final List<String> modes;
    private final Map<String, Integer> hiddenModes = new HashMap<>();

    public ModeSetting(String name, String defaultValue, String... modes) {
        super(name, defaultValue);
        this.modes = Collections.unmodifiableList(Arrays.asList(modes));
        if (!this.modes.contains(defaultValue)) throw new IllegalArgumentException(name + ": default " + defaultValue + " is not one of " + this.modes);
    }

    public String get() { return value; }

    public boolean is(String mode) { return value.equalsIgnoreCase(mode); }

    public int index() { return modes.indexOf(value); }

    public List<String> getModes() { return modes; }

    /** Mark individual choices without changing their original index, saved value or runtime behavior. */
    public ModeSetting hideModes(int level, String... options) {
        GuiVisibility.checkLevel(level);
        for (String option : options) if (fix(option) == null)
            throw new IllegalArgumentException(getName() + ": unknown mode " + option);
        for (String option : options) {
            String canonical = fix(option);
            if (level == 0) hiddenModes.remove(canonical);
            else hiddenModes.put(canonical, level);
        }
        return this;
    }

    public ModeSetting hideModes(String... options) { return hideModes(2, options); }

    public List<String> getVisibleModes() {
        List<String> out = new ArrayList<>();
        for (String option : modes) {
            Integer level = hiddenModes.get(option);
            if (level == null || !GuiVisibility.hideFrom(level)) out.add(option);
        }
        return Collections.unmodifiableList(out);
    }

    public int visibleIndex() { return getVisibleModes().indexOf(value); }

    /** A hidden active value stays active, but its name is not exposed by the GUI. */
    public String visibleDisplay() { return visibleIndex() < 0 ? "请选择" : value; }

    public boolean setVisibleIndex(int index) {
        List<String> options = getVisibleModes();
        if (index < 0 || index >= options.size()) return false;
        set(options.get(index));
        return true;
    }

    public void cycleVisible(boolean forward) {
        int step = forward ? 1 : -1;
        List<String> options = getVisibleModes();
        for (int distance = 1; distance <= modes.size(); distance++) {
            int i = (index() + step * distance + modes.size()) % modes.size();
            if (options.contains(modes.get(i))) { set(modes.get(i)); return; }
        }
    }

    @Override public boolean isVisible() { return super.isVisible() && !getVisibleModes().isEmpty(); }

    public void set(String mode) { setValue(mode); }

    public void cycle(boolean forward) {
        int i = index() + (forward ? 1 : -1);
        setValue(modes.get((i + modes.size()) % modes.size()));
    }

    @Override
    protected String fix(String v) {
        if (v == null) return null;
        for (String m : modes) if (m.equalsIgnoreCase(v.trim())) return m;
        return null;
    }

    @Override
    public boolean parse(String text) {
        String m = fix(text);
        if (m == null) return false;
        set(m);
        return true;
    }

    @Override
    public String display() { return value; }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) setValue(json.getAsString());
    }
}
