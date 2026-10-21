package gale.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Free text. */
public final class TextSetting extends Setting<String> {
    public TextSetting(String name, String defaultValue) { super(name, defaultValue); }

    public String get() { return value; }

    public void set(String text) { setValue(text); }

    @Override
    public boolean parse(String text) {
        set(text);
        return true;
    }

    @Override
    public String display() { return value; }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) set(json.getAsString());
    }
}
