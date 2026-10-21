package gale.meowtils.api.gui.values;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.gui.Fields;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ModeValue {
    private final String name;
    private final List<String> modes;
    private final Field field;
    private final Object owner;

    public ModeValue(String name, List<String> modes, String fieldName, Object owner) {
        if (modes.isEmpty()) throw new IllegalArgumentException("Mode list must not be empty!");
        this.name = name;
        this.modes = new ArrayList<>(modes);
        this.owner = owner;
        this.field = Fields.bind(owner, fieldName);
        String stored = Fields.get(field, owner, null);
        if (stored == null || !this.modes.contains(stored)) Fields.set(field, owner, this.modes.get(0));
    }

    public String getName() { return name; }

    public List<String> getModes() { return Collections.unmodifiableList(modes); }

    public String getValue() {
        String v = Fields.get(field, owner, null);
        return v == null || !modes.contains(v) ? modes.get(0) : v;
    }

    public boolean is(String mode) { return getValue().equals(mode); }

    public void setValue(String value) {
        if (!modes.contains(value)) throw new IllegalArgumentException("Mode not found: " + value);
        Fields.set(field, owner, value);
        ConfigManager.save();
    }

    public void setValue(int index) {
        if (index < 0 || index >= modes.size()) throw new IndexOutOfBoundsException("Mode index out of range");
        setValue(modes.get(index));
    }
}
