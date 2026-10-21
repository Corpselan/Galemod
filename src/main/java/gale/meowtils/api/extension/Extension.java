package gale.meowtils.api.extension;

import gale.Gale;
import gale.meowtils.ExtensionLoader;
import gale.meowtils.api.event.api.EventManager;
import gale.meowtils.api.gui.ColorLink;
import gale.meowtils.api.gui.Module;
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

import java.util.List;
import java.util.function.Consumer;

/** Meowtils' extension module: what a .meowtils extension builds its modules on. */
public abstract class Extension extends Module {
    private final String author, moduleName;
    public final Object owner = this;

    protected Extension(String moduleName, String author) {
        super(moduleName, Module.Category.Extensions);
        tag(Module.ModuleTag.LEGIT);
        this.author = author;
        this.moduleName = moduleName;
    }

    public Object getOwner() { return this; }

    public String getAuthor() { return author; }

    private void add(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Gale.LOG.error("{} failed to add {} component", moduleName, what, t);
        }
    }

    public void toggle(String name, String config) { add("toggle", () -> addToggle(new ToggleValue(name, config, this))); }

    public void check(String name, String config) { add("check", () -> addCheck(new CheckValue(name, config, this))); }

    public void slider(String name, double min, double max, double increment, String valueType, String config, Class<?> targetType) {
        add("slider", () -> addSlider(new SliderValue(name, min, max, increment, valueType, config, this, targetType)));
    }

    public void mode(String name, List<String> modes, String config) { add("mode", () -> addMode(new ModeValue(name, modes, config, this))); }

    public void text(String name, String config) { add("text", () -> addText(new TextValue(name, config, this))); }

    public void text(String name, String description, String config) { add("text", () -> addText(new TextValue(name, description, config, this))); }

    public void color(String name, ColorLink link) { add("color", () -> addColor(new ColorValue(name, link))); }

    public void saturation(ColorLink link) { add("saturation", () -> addSaturation(new SaturationValue(link))); }

    public void brightness(ColorLink link) { add("brightness", () -> addBrightness(new BrightnessValue(link))); }

    public void opacity(String name, String config) { add("opacity", () -> addOpacity(new OpacityValue(name, config, this))); }

    @Deprecated
    public void opacity(String name, String config, ColorLink link) { opacity(name, config); }

    public void info(String name) { tooltip(author != null ? name + "\n\u00a79Author: \u00a79" + author : name); }

    public void button(String name, float textScale, Runnable action) { add("button", () -> addButton(new ButtonValue(name, textScale, action))); }

    public void bind(String name, String config) { add("bind", () -> addBind(new BindValue(name, config, this))); }

    public void expand(String name, Consumer<ExpandValue> builder) { add("expand", () -> addExpand(new ExpandValue(name, builder, this))); }

    public ColorLink linkColor(String redConfig, String greenConfig, String blueConfig) { return new ColorLink(redConfig, greenConfig, blueConfig, this); }

    public static void registerModule(Module module) {
        if (module == null) throw new IllegalArgumentException("Module cannot be null");
        ExtensionLoader.registerModule(module);
    }

    public static void registerEvent(Object listener) {
        try {
            EventManager.register(listener);
        } catch (Throwable t) {
            Gale.LOG.error("Unable to register event {}", listener, t);
        }
    }
}
