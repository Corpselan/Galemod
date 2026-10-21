package gale.meowtils.api.gui.values;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.gui.Fields;

import java.lang.reflect.Field;

public class ToggleValue {
    private final String name;
    private final Field field;
    private final Object owner;

    public ToggleValue(String name, String fieldName, Object owner) {
        this.name = name;
        this.owner = owner;
        this.field = Fields.bind(owner, fieldName);
    }

    public String getName() { return name; }

    public boolean getState() { return Fields.get(field, owner, false); }

    public void setState(boolean state) {
        if (state == getState()) return;
        Fields.set(field, owner, state);
        ConfigManager.save();
    }

    public void toggle() { setState(!getState()); }
}
