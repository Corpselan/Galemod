package gale.meowtils.api.gui.values;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.gui.Fields;

import java.lang.reflect.Field;

public class TextValue {
    private final String name, description;
    private final Field field;
    private final Object owner;

    public TextValue(String name, String fieldName, Object owner) { this(name, "Type...", fieldName, owner); }

    public TextValue(String name, String description, String fieldName, Object owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.field = Fields.bind(owner, fieldName);
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String get() { return Fields.get(field, owner, ""); }

    public void set(String value) {
        if (value == null) value = "";
        if (value.equals(get())) return;
        Fields.set(field, owner, value);
        ConfigManager.save();
    }
}
