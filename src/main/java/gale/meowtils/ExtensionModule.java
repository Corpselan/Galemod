package gale.meowtils;

import gale.meowtils.api.extension.Extension;
import gale.meowtils.api.gui.ColorLink;
import gale.meowtils.api.gui.values.BindValue;
import gale.meowtils.api.gui.values.BrightnessValue;
import gale.meowtils.api.gui.values.ButtonValue;
import gale.meowtils.api.gui.values.CheckValue;
import gale.meowtils.api.gui.values.ColorValue;
import gale.meowtils.api.gui.values.ExpandValue;
import gale.meowtils.api.gui.values.ModeValue;
import gale.meowtils.api.gui.values.SaturationValue;
import gale.meowtils.api.gui.values.SliderValue;
import gale.meowtils.api.gui.values.TextValue;
import gale.meowtils.api.gui.values.ToggleValue;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import gale.module.setting.TextSetting;
import gale.util.KeyUtil;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A module an extension made, as a Gale module in the Extensions category: switching it switches the extension's
 * module (its onEnable / onDisable), its values are Gale settings (saved in Gale's config), its key is Gale's.
 */
public final class ExtensionModule extends Module {
    public final gale.meowtils.api.gui.Module ext;
    public final String file;
    private final List<Runnable> pulls = new ArrayList<>();
    private final Set<ColorLink> colors = new HashSet<>();
    private boolean syncing;

    ExtensionModule(gale.meowtils.api.gui.Module ext, String name, String file) {
        super(name, Category.EXTENSIONS, describe(ext, file));
        this.ext = ext;
        this.file = file;
        if (ext.alwaysEnabled) alwaysOn();
        setKey(ext.getKey());
        for (Object v : ext.getOrderedValues()) add(v, null);
        // the extension switching its own module (setState): the Gale module follows
        ext.galeListener = on -> {
            if (syncing || isEnabled() == on) return;
            syncing = true;
            try {
                setEnabled(on);
            } finally {
                syncing = false;
            }
        };
    }

    private static String describe(gale.meowtils.api.gui.Module ext, String file) {
        String tip = ext.getTooltip() == null ? "" : EnumChatFormatting.getTextWithoutFormattingCodes(ext.getTooltip()).split("\n")[0].trim();
        String author = ext instanceof Extension && ((Extension) ext).getAuthor() != null ? "作者 " + ((Extension) ext).getAuthor() + "，" : "";
        return (tip.isEmpty() ? "" : tip + " ") + "（Meowtils 扩展，" + author + file + "）";
    }

    @Override
    protected void onEnable() { pass(true); }

    @Override
    protected void onDisable() { pass(false); }

    private void pass(boolean on) {
        if (syncing) return;
        syncing = true;
        try {
            ext.setState(on);
        } finally {
            syncing = false;
        }
    }

    /** Every tick: what the extension changed itself (its fields, its key) shows in the settings. */
    void pull() {
        for (Runnable r : pulls) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
        if (ext.getKey() != getKey()) ext.setKey(getKey());
    }

    private String unique(String name) {
        String n = name == null || name.trim().isEmpty() ? "Value" : name.trim();
        String u = n;
        for (int i = 2; getSetting(u) != null; i++) u = n + " " + i;
        return u;
    }

    private void add(Object v, String group) {
        Setting<?> s = null;
        if (v instanceof ToggleValue || v instanceof CheckValue) {
            boolean check = v instanceof CheckValue;
            String name = check ? ((CheckValue) v).getName() : ((ToggleValue) v).getName();
            BoolSetting b = bool(unique(name), check ? ((CheckValue) v).getState() : ((ToggleValue) v).getState());
            b.onChange(on -> {
                if (check) ((CheckValue) v).setState(on);
                else ((ToggleValue) v).setState(on);
            });
            pulls.add(() -> {
                boolean now = check ? ((CheckValue) v).getState() : ((ToggleValue) v).getState();
                if (b.get() != now) b.set(now);
            });
            s = b;
        } else if (v instanceof SliderValue) {
            SliderValue sv = (SliderValue) v;
            NumberSetting n = number(unique(sv.getName()), sv.get(), sv.getMin(), sv.getMax(), sv.getIncrement());
            if (sv.getValueType() != null) n.suffix(sv.getValueType());
            n.onChange(sv::set);
            pulls.add(() -> {
                double now = sv.get();
                if (Math.abs(n.get() - now) > 1e-9) n.set(now);
            });
            s = n;
        } else if (v instanceof ModeValue) {
            ModeValue mv = (ModeValue) v;
            ModeSetting m = mode(unique(mv.getName()), mv.getValue(), mv.getModes().toArray(new String[0]));
            m.onChange(mv::setValue);
            pulls.add(() -> {
                if (!m.get().equals(mv.getValue())) m.set(mv.getValue());
            });
            s = m;
        } else if (v instanceof TextValue) {
            TextValue tv = (TextValue) v;
            TextSetting t = text(unique(tv.getName() == null ? "Text" : tv.getName()), tv.get());
            t.onChange(tv::set);
            pulls.add(() -> {
                if (!t.get().equals(tv.get())) t.set(tv.get());
            });
            s = t;
        } else if (v instanceof ColorValue) {
            ColorLink link = ((ColorValue) v).getLink();
            colors.add(link);
            ColorSetting c = color(unique(((ColorValue) v).getName()), 0xFF000000 | link.getRGB());
            c.onChange(argb -> link.setRGB(argb & 0xFFFFFF));
            pulls.add(() -> {
                if ((c.getValue() & 0xFFFFFF) != link.getRGB() && !c.isRainbow()) c.set(0xFF000000 | link.getRGB());
            });
            s = c;
        } else if (v instanceof SaturationValue || v instanceof BrightnessValue) {
            // part of a colour: shown with its colour setting; on its own, a number
            boolean sat = v instanceof SaturationValue;
            ColorLink link = sat ? ((SaturationValue) v).getLink() : ((BrightnessValue) v).getLink();
            if (colors.contains(link)) return;
            NumberSetting n = number(unique(sat ? "Saturation" : "Brightness"), sat ? ((SaturationValue) v).get() : ((BrightnessValue) v).get(), 0, 100, 1).suffix("%");
            n.onChange(x -> {
                if (sat) ((SaturationValue) v).set(x);
                else ((BrightnessValue) v).set(x);
            });
            s = n;
        } else if (v instanceof ButtonValue) {
            // a button: switching it on presses it, and it goes back off
            ButtonValue bv = (ButtonValue) v;
            BoolSetting b = bool(unique(bv.getName()), false);
            b.onChange(on -> {
                if (!on) return;
                b.set(false);
                bv.press();
            });
            s = b;
        } else if (v instanceof BindValue) {
            BindValue kv = (BindValue) v;
            TextSetting t = text(unique(kv.getName()), KeyUtil.name(kv.getBind()));
            t.onChange(name -> {
                int code = KeyUtil.code(name);
                if (code >= 0) kv.setBind(code);
            });
            s = t;
        } else if (v instanceof ExpandValue) {
            ExpandValue ev = (ExpandValue) v;
            for (Object sub : ev.getSubValues()) add(sub, ev.getName());
            return;
        }
        if (s != null && group != null) s.describe(group);
    }
}
