package gale.module.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.concurrent.ThreadLocalRandom;

/** A range low..high within min..max (CPS 8-12, delay 50-100 ms...). The value is {low, high}. */
public final class RangeSetting extends Setting<double[]> {
    public final double min, max, step;
    private String suffix = "";

    public RangeSetting(String name, double low, double high, double min, double max, double step) {
        super(name, new double[]{ low, high });
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = fix(new double[]{ low, high });
    }

    public double low() { return value[0]; }

    public double high() { return value[1]; }

    public void set(double low, double high) { setValue(new double[]{ low, high }); }

    /** A random value in the range. */
    public double random() { return low() >= high() ? low() : ThreadLocalRandom.current().nextDouble(low(), high()); }

    public RangeSetting suffix(String suffix) {
        this.suffix = suffix;
        return this;
    }

    public String getSuffix() { return suffix; }

    @Override
    public void setValue(double[] v) {
        double[] fixed = fix(v);
        if (fixed == null || fixed[0] == value[0] && fixed[1] == value[1]) return;
        super.setValue(fixed);
    }

    @Override
    protected double[] fix(double[] v) {
        if (v == null || v.length != 2) return null;
        double a = snap(v[0]), b = snap(v[1]);
        return a <= b ? new double[]{ a, b } : new double[]{ b, a };
    }

    private double snap(double d) {
        d = Math.max(min, Math.min(max, d));
        if (step > 0) d = min + Math.round((d - min) / step) * step;
        return Math.round(Math.max(min, Math.min(max, d)) * 1e6) / 1e6;
    }

    @Override
    public boolean parse(String text) {
        String[] p = text.trim().split("\\s*[-~, ]\\s*");
        try {
            if (p.length == 1) { double d = Double.parseDouble(p[0]); set(d, d); return true; }
            if (p.length == 2) { set(Double.parseDouble(p[0]), Double.parseDouble(p[1])); return true; }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }

    @Override
    public String display() { return NumberSetting.format(low()) + "-" + NumberSetting.format(high()) + suffix; }

    @Override
    public JsonElement toJson() {
        JsonArray a = new JsonArray();
        a.add(new com.google.gson.JsonPrimitive(low()));
        a.add(new com.google.gson.JsonPrimitive(high()));
        return a;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json != null && json.isJsonArray() && json.getAsJsonArray().size() == 2)
            set(json.getAsJsonArray().get(0).getAsDouble(), json.getAsJsonArray().get(1).getAsDouble());
    }
}
