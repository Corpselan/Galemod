package gale.meowtils.api.gui.values;

import gale.meowtils.api.gui.ColorLink;

public class SaturationValue {
    private final ColorLink link;

    public SaturationValue(ColorLink link) { this.link = link; }

    public double get() { return link.getSaturation() * 100.0; }

    public void set(double v) { link.apply(link.getHue(), (float) (Math.max(0, Math.min(100, v)) / 100.0), link.getBrightness()); }

    public ColorLink getLink() { return link; }
}
