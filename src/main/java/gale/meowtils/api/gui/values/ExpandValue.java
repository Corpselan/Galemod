package gale.meowtils.api.gui.values;

import gale.meowtils.api.gui.ColorLink;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Meowtils: a group of values that opens and closes. In Gale they are the module's settings, described by the group's name. */
public class ExpandValue {
    private final String name;
    private final Object owner;
    private final List<Object> values = new ArrayList<>();
    private boolean state;

    public ExpandValue(String name, Consumer<ExpandValue> builder) { this(name, builder, null); }

    public ExpandValue(String name, Consumer<ExpandValue> builder, Object owner) {
        this.name = name;
        this.owner = owner;
        if (builder != null) builder.accept(this);
    }

    public void addBind(BindValue v) { values.add(v); }

    public void addBrightness(BrightnessValue v) { values.add(v); }

    public void addButton(ButtonValue v) { values.add(v); }

    public void addCheck(CheckValue v) { values.add(v); }

    public void addColor(ColorValue v) { values.add(v); }

    public void addMode(ModeValue v) { values.add(v); }

    public void addOpacity(OpacityValue v) { values.add(v); }

    public void addSaturation(SaturationValue v) { values.add(v); }

    public void addSlider(SliderValue v) { values.add(v); }

    public void addText(TextValue v) { values.add(v); }

    public void addToggle(ToggleValue v) { values.add(v); }

    public List<Object> getSubValues() { return values; }

    public String getName() { return name; }

    public boolean getState() { return state; }

    public void setState(boolean state) { this.state = state; }

    public void toggle() { state = !state; }

    public void toggle(String name, String config) { addToggle(new ToggleValue(name, config, owner)); }

    public void check(String name, String config) { addCheck(new CheckValue(name, config, owner)); }

    public void slider(String name, double min, double max, double increment, String valueType, String config, Class<?> targetType) {
        addSlider(new SliderValue(name, min, max, increment, valueType, config, owner, targetType));
    }

    public void mode(String name, List<String> modes, String config) { addMode(new ModeValue(name, modes, config, owner)); }

    public void text(String name, String config) { addText(new TextValue(name, config, owner)); }

    public void text(String name, String description, String config) { addText(new TextValue(name, description, config, owner)); }

    public void color(String name, ColorLink link) { addColor(new ColorValue(name, link)); }

    public void saturation(ColorLink link) { addSaturation(new SaturationValue(link)); }

    public void brightness(ColorLink link) { addBrightness(new BrightnessValue(link)); }

    public void opacity(String name, String config) { addOpacity(new OpacityValue(name, config, owner)); }

    public void button(String name, float textScale, Runnable action) { addButton(new ButtonValue(name, textScale, action)); }

    public void bind(String name, String config) { addBind(new BindValue(name, config, owner)); }
}
