package gale.meowtils.api.gui.values;

import gale.meowtils.api.gui.ColorLink;

public class BrightnessValue {
    private final ColorLink link;

    public BrightnessValue(ColorLink link) { this.link = link; }

    public double get() { return link.getBrightness() * 100.0; }

    public void set(double v) { link.apply(link.getHue(), link.getSaturation(), (float) (Math.max(0, Math.min(100, v)) / 100.0)); }

    public ColorLink getLink() { return link; }
}
