package gale.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** On or off. */
public final class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String name, boolean defaultValue) { super(name, defaultValue); }

    public boolean get() { return value; }

    public void set(boolean on) { setValue(on); }

    public void toggle() { setValue(!value); }

    @Override
    public boolean parse(String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("true") || t.equals("on") || t.equals("1") || t.equals("yes")) { set(true); return true; }
        if (t.equals("false") || t.equals("off") || t.equals("0") || t.equals("no")) { set(false); return true; }
        if (t.equals("toggle")) { toggle(); return true; }
        return false;
    }

    @Override
    public String display() { return value ? "on" : "off"; }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) set(json.getAsBoolean());
    }
}
