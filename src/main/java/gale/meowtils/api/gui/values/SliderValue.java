package gale.meowtils.api.gui.values;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.gui.Fields;

import java.lang.reflect.Field;

public class SliderValue {
    private final String name, valueType;
    private final double min, max, increment;
    private final Field field;
    private final Object owner;
    private final Class<?> targetType;
    private double value;

    public SliderValue(String name, double min, double max, double increment, String valueType, String fieldName, Object owner, Class<?> targetType) {
        this.name = name;
        this.min = min;
        this.max = max;
        this.increment = increment <= 0 ? 0.01 : increment;
        this.valueType = valueType;
        this.owner = owner;
        this.targetType = targetType;
        this.field = Fields.bind(owner, fieldName);
        syncFromConfig();
    }

    public String getName() { return name; }

    public double get() {
        syncFromConfig();
        return value;
    }

    public double getMin() { return min; }

    public double getMax() { return max; }

    public double getIncrement() { return increment; }

    public String getValueType() { return valueType; }

    public void set(double v) {
        value = snap(Math.max(min, Math.min(max, v)));
        Fields.set(field, owner, Fields.cast(value, targetType == null ? double.class : targetType));
        ConfigManager.save();
    }

    public void syncFromConfig() {
        Number n = Fields.get(field, owner, null);
        if (n != null) value = snap(n.doubleValue());
    }

    private double snap(double v) {
        double s = min + Math.round((v - min) / increment) * increment;
        return Math.round(s * 1e6) / 1e6;
    }

    public String getFormattedValue() {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.valueOf(Math.round(value * 1000.0) / 1000.0);
    }
}
