package gale.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import gale.util.ColorUtil;

/** A colour, 0xAARRGGBB. Typed as #RRGGBB or #AARRGGBB, or "rainbow". */
public final class ColorSetting extends Setting<Integer> {
    private boolean rainbow;

    public ColorSetting(String name, int argb) { super(name, argb); }

    /** The colour to draw with now (moves when rainbow is on). */
    public int get() { return rainbow ? ColorUtil.withAlpha(ColorUtil.rainbow(4000, 0, 0.6f, 1f), ColorUtil.alpha(value)) : value; }

    public int get(int alpha) { return ColorUtil.withAlpha(get(), alpha); }

    public boolean isRainbow() { return rainbow; }

    public void setRainbow(boolean rainbow) {
        if (this.rainbow == rainbow) return;
        this.rainbow = rainbow;
        fireChanged();
    }

    public void set(int argb) { setValue(argb); }

    @Override
    public boolean parse(String text) {
        String t = text.trim().toLowerCase();
        if (t.equals("rainbow")) { setRainbow(true); return true; }
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0x")) t = t.substring(2);
        try {
            long v = Long.parseLong(t, 16);
            setRainbow(false);
            set((int) (t.length() <= 6 ? 0xFF000000L | v : v));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String display() { return rainbow ? "rainbow" : String.format("#%08X", value); }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(display()); }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive()) parse(json.getAsString());
    }
}
