package gale.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A number between min and max, in steps of step. */
public final class NumberSetting extends Setting<Double> {
    public final double min, max, step;
    private String suffix = "";

    public NumberSetting(String name, double defaultValue, double min, double max, double step) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = fix(defaultValue);
    }

    public double get() { return value; }

    public int getInt() { return (int) Math.round(value); }

    public float getFloat() { return (float) (double) value; }

    public void set(double v) { setValue(v); }

    /** Shown after the number, e.g. "ms", "%", " blocks". */
    public NumberSetting suffix(String suffix) {
        this.suffix = suffix;
        return this;
    }

    public String getSuffix() { return suffix; }

    /** Where the value sits between min and max, 0..1 (for sliders). */
    public double fraction() { return max == min ? 0 : (value - min) / (max - min); }

    public void setFraction(double f) { set(min + Math.max(0, Math.min(1, f)) * (max - min)); }

    @Override
    protected Double fix(Double v) {
        if (v == null || v.isNaN()) return null;
        double d = Math.max(min, Math.min(max, v));
        if (step > 0) d = min + Math.round((d - min) / step) * step;
        d = Math.max(min, Math.min(max, d));
        return Math.round(d * 1e6) / 1e6;
    }

    @Override
    public boolean parse(String text) {
        try {
            set(Double.parseDouble(text.trim().replace(suffix.trim(), "")));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String display() { return format(value) + suffix; }

    static String format(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.valueOf((long) d);
        String s = String.format(java.util.Locale.ROOT, "%.3f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
    }
}
