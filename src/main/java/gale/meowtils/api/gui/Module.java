package gale.meowtils.api.gui;

import gale.meowtils.api.config.ConfigManager;
import gale.meowtils.api.event.api.EventManager;
import gale.meowtils.api.gui.values.BindValue;
import gale.meowtils.api.gui.values.BrightnessValue;
import gale.meowtils.api.gui.values.ButtonValue;
import gale.meowtils.api.gui.values.CheckValue;
import gale.meowtils.api.gui.values.ColorValue;
import gale.meowtils.api.gui.values.ExpandValue;
import gale.meowtils.api.gui.values.ModeValue;
import gale.meowtils.api.gui.values.OpacityValue;
import gale.meowtils.api.gui.values.SaturationValue;
import gale.meowtils.api.gui.values.SliderValue;
import gale.meowtils.api.gui.values.TextValue;
import gale.meowtils.api.gui.values.ToggleValue;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Meowtils' module, as extensions build on it. Its state and key live in the subclass's fields "enabled" and "key"
 * (Meowtils' @Config fields); its values in other fields. In Gale each one shows up as a Gale module (gale.meowtils.ExtensionModule).
 */
public abstract class Module {
    protected Minecraft mc = Minecraft.getMinecraft();
    private String name;
    protected String moduleName;
    private boolean keyHeld, enabled;
    private final Category category;
    private final ArrayList<ColorValue> rgb = new ArrayList<>();
    private final ArrayList<SaturationValue> saturation = new ArrayList<>();
    private final ArrayList<BrightnessValue> brightness = new ArrayList<>();
    private final ArrayList<ToggleValue> booleans = new ArrayList<>();
    private final ArrayList<CheckValue> checks = new ArrayList<>();
    private final ArrayList<ModeValue> arrays = new ArrayList<>();
    private final ArrayList<TextValue> strings = new ArrayList<>();
    private final ArrayList<SliderValue> values = new ArrayList<>();
    private final ArrayList<OpacityValue> opacity = new ArrayList<>();
    private final ArrayList<ButtonValue> button = new ArrayList<>();
    private final ArrayList<BindValue> bind = new ArrayList<>();
    private final ArrayList<ExpandValue> expand = new ArrayList<>();
    private final List<Object> orderedValues = new ArrayList<>();
    private String tooltip;
    private ModuleTag tag;
    public boolean alwaysEnabled;
    private Field keyField, stateField;
    /** Gale: told when the module turns itself on or off (its Gale module follows) */
    public Consumer<Boolean> galeListener;

    public Module(String name, Category category) {
        this.name = name;
        this.moduleName = name;
        this.category = category;
        this.keyField = Fields.bind(this, "key");
        this.stateField = Fields.bind(this, "enabled");
    }

    public Module(String name, Category category, boolean alwaysEnabled) {
        this(name, category);
        this.alwaysEnabled = alwaysEnabled;
        if (alwaysEnabled) {
            enabled = true;
            EventManager.register(this);
        }
    }

    public void onEnable() { }

    public void onDisable() { }

    public void onReset() { }

    public void setState(boolean on) {
        if (alwaysEnabled && !on || enabled == on) return;
        enabled = on;
        if (on) {
            EventManager.register(this);
            onEnable();
        } else {
            EventManager.unregister(this);
            onDisable();
        }
        if (!alwaysEnabled && stateField != null) {
            Fields.set(stateField, this, on);
            ConfigManager.save();
        }
        if (galeListener != null) galeListener.accept(on);
    }

    public void toggle() { setState(!enabled); }

    public void addToggle(ToggleValue v) { booleans.add(v); orderedValues.add(v); }

    public void addCheck(CheckValue v) { checks.add(v); orderedValues.add(v); }

    public void addMode(ModeValue v) { arrays.add(v); orderedValues.add(v); }

    public void addText(TextValue v) { strings.add(v); orderedValues.add(v); }

    public void addSlider(SliderValue v) { values.add(v); orderedValues.add(v); }

    public void addButton(ButtonValue v) { button.add(v); orderedValues.add(v); }

    public void addBind(BindValue v) { bind.add(v); orderedValues.add(v); }

    public void addExpand(ExpandValue v) { expand.add(v); orderedValues.add(v); }

    public void addColor(ColorValue v) { rgb.add(v); orderedValues.add(v); }

    public void addSaturation(SaturationValue v) { saturation.add(v); orderedValues.add(v); }

    public void addBrightness(BrightnessValue v) { brightness.add(v); orderedValues.add(v); }

    public void addOpacity(OpacityValue v) { opacity.add(v); orderedValues.add(v); }

    public Module tooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public Module tag(ModuleTag tag) {
        this.tag = tag;
        return this;
    }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public boolean isKeyHeld() { return keyHeld; }

    public void setKeyHeld(boolean keyHeld) { this.keyHeld = keyHeld; }

    public void setKey(int key) {
        if (keyField != null) {
            Fields.set(keyField, this, key);
            ConfigManager.save();
        }
    }

    /** The saved state (Meowtils reads the "enabled" field); {@link #isOn} is whether it runs now. */
    public boolean getState() { return stateField == null ? enabled : Fields.get(stateField, this, false); }

    /** Gale: whether onEnable has run and onDisable not since */
    public boolean isOn() { return enabled; }

    public int getKey() { return Fields.<Number>get(keyField, this, 0).intValue(); }

    public Category getCategory() { return category; }

    public ArrayList<ToggleValue> getBooleans() { return booleans; }

    public ArrayList<CheckValue> getChecks() { return checks; }

    public ArrayList<ModeValue> getArrays() { return arrays; }

    public ArrayList<TextValue> getStrings() { return strings; }

    public ArrayList<SliderValue> getValues() { return values; }

    public ArrayList<ColorValue> getRgb() { return rgb; }

    public ArrayList<SaturationValue> getSaturation() { return saturation; }

    public ArrayList<BrightnessValue> getBrightness() { return brightness; }

    public ArrayList<OpacityValue> getOpacity() { return opacity; }

    public ArrayList<ButtonValue> getButton() { return button; }

    public ArrayList<BindValue> getBind() { return bind; }

    public ArrayList<ExpandValue> getExpand() { return expand; }

    public List<Object> getOrderedValues() { return orderedValues; }

    public String getTooltip() { return tooltip; }

    public ModuleTag getTag() { return tag; }

    public static ArrayList<Module> getCategoryModules(Category cat) {
        ArrayList<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.getModules()) if (m.getCategory() == cat) out.add(m);
        return out;
    }

    public static <T extends Module> T get(Class<T> type) { return ModuleManager.get(type); }

    public void reset() { onReset(); }

    public enum Category { Meowtils, Hypixel, Skywars, Bedwars, Render, Antisnipe, Utility, Advanced, Extensions }

    public enum ModuleTag { LEGIT, SAFE, BLATANT }
}
