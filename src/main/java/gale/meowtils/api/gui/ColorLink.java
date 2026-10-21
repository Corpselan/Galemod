package gale.meowtils.api.gui;

import gale.meowtils.api.config.ConfigManager;

import java.awt.Color;
import java.lang.reflect.Field;

/** Meowtils: a colour kept in three int fields (red, green, blue) of the module. */
public class ColorLink {
    private final Field redField, greenField, blueField;
    private final Object owner;
    private float hue, saturation, brightness;
    private int lastRed = -1, lastGreen = -1, lastBlue = -1;

    public ColorLink(String red, String green, String blue, Object owner) {
        this.owner = owner;
        this.redField = Fields.bind(owner, red);
        this.greenField = Fields.bind(owner, green);
        this.blueField = Fields.bind(owner, blue);
        sync();
    }

    private void sync() {
        Number r = Fields.get(redField, owner, null), g = Fields.get(greenField, owner, null), b = Fields.get(blueField, owner, null);
        if (r == null || g == null || b == null) return;
        if (r.intValue() == lastRed && g.intValue() == lastGreen && b.intValue() == lastBlue) return;
        float[] hsb = Color.RGBtoHSB(r.intValue(), g.intValue(), b.intValue(), null);
        hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
        lastRed = r.intValue();
        lastGreen = g.intValue();
        lastBlue = b.intValue();
    }

    public void apply(float hue, float sat, float bri) {
        this.hue = hue;
        this.saturation = sat;
        this.brightness = bri;
        int rgb = Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
        lastRed = rgb >> 16 & 0xFF;
        lastGreen = rgb >> 8 & 0xFF;
        lastBlue = rgb & 0xFF;
        Fields.set(redField, owner, lastRed);
        Fields.set(greenField, owner, lastGreen);
        Fields.set(blueField, owner, lastBlue);
        ConfigManager.save();
    }

    public float getHue() { sync(); return hue; }

    public float getSaturation() { sync(); return saturation; }

    public float getBrightness() { sync(); return brightness; }

    public int getPureHueRGB() { return Color.HSBtoRGB(getHue(), 1f, 1f) & 0xFFFFFF; }

    public int getRGB() { sync(); return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF; }

    /** Gale: set from an RGB colour. */
    public void setRGB(int rgb) {
        float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
        apply(hsb[0], hsb[1], hsb[2]);
    }
}
