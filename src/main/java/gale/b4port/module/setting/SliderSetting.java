package gale.b4port.module.setting;

import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;

import java.util.HashMap;

/**
 * B4's slider over a Gale setting: a number slider is a {@link NumberSetting}; a slider with options (B4's modes) is a
 * {@link ModeSetting}, its input the option's index.
 */
public class SliderSetting extends Setting {
    private final NumberSetting number;
    private final ModeSetting mode;
    public final String prefix, suffix;
    public final double min, max, interval, defaultValue;
    public final String[] options;
    public final HashMap<Double, String> labels;

    public SliderSetting(String name, NumberSetting number, String prefix, String suffix, HashMap<Double, String> labels) {
        super(name);
        this.number = number;
        this.mode = null;
        this.prefix = prefix == null ? "" : prefix;
        this.suffix = suffix == null ? "" : suffix;
        this.min = number.min;
        this.max = number.max;
        this.interval = number.step;
        this.defaultValue = number.getDefault();
        this.options = null;
        this.labels = labels;
    }

    public SliderSetting(String name, ModeSetting mode, HashMap<Double, String> labels) {
        super(name);
        this.number = null;
        this.mode = mode;
        this.prefix = "";
        this.suffix = "";
        this.options = mode.getModes().toArray(new String[0]);
        this.min = 0;
        this.max = options.length - 1;
        this.interval = 1;
        this.defaultValue = mode.getModes().indexOf(mode.getDefault());
        this.labels = labels;
    }

    /** A slider nobody can change (for a module Gale only knows by name): always its default. */
    public static SliderSetting fixed(String name, double value) {
        return new SliderSetting(name, new NumberSetting(name, value, Math.min(value, 0), Math.max(value, 1), 0.0001), "", "", null);
    }

    public boolean isMode() {
        return this.options != null;
    }

    public double getInput() {
        return mode != null ? Math.max(0, mode.index()) : number.get();
    }

    public boolean isAtMin() {
        return this.getInput() == this.min;
    }

    public boolean isNotDefault() {
        return this.getInput() != this.defaultValue;
    }

    public double getMin() {
        return this.min;
    }

    public double getMax() {
        return this.max;
    }

    public void setValue(double d) {
        if (mode != null) {
            int i = (int) Math.round(d);
            if (i >= 0 && i < options.length) mode.set(options[i]);
        } else {
            number.set(d);
        }
    }

    public static void order(SliderSetting min, SliderSetting max) {
        if (min.getInput() > max.getInput()) {
            double d = min.getInput();
            min.setValue(max.getInput());
            max.setValue(d);
        }
    }

    public String getDisplay(boolean colored) {
        String s;
        double value = getInput();
        if (this.isMode()) {
            if (colored) {
                if (this.labels != null && (s = this.labels.get(value)) != null) return s;
                return "§e" + this.options[(int) value];
            }
            return this.options[(int) value];
        }
        if (this.labels != null && (s = this.labels.get(value)) != null) return s;
        double d = (double) Math.round(value * 1000.0) / 1000.0;
        return (colored ? "§b" : "") + this.prefix + formatNumber(d) + this.suffix;
    }

    public static String formatNumber(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
    }

    @Override
    public gale.module.setting.Setting<?> gale() {
        return mode != null ? mode : number;
    }
}
