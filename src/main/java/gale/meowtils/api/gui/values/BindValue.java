package gale.meowtils.api.gui.values;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.gui.Fields;

import java.lang.reflect.Field;

public class BindValue {
    private final String name;
    private final Field field;
    private final Object owner;

    public BindValue(String name, String fieldName, Object owner) {
        this.name = name;
        this.owner = owner;
        this.field = Fields.bind(owner, fieldName);
    }

    public String getName() { return name; }

    public int getBind() { return Fields.<Number>get(field, owner, 0).intValue(); }

    public void setBind(int bind) {
        if (bind == getBind()) return;
        Fields.set(field, owner, bind);
        ConfigManager.save();
    }
}
