package gale.util;

import java.awt.Color;

/** Colours as ints, 0xAARRGGBB. */
public final class ColorUtil {
    private ColorUtil() { }

    public static int argb(int a, int r, int g, int b) {
        return (MathUtil.clamp(a, 0, 255) << 24) | (MathUtil.clamp(r, 0, 255) << 16) | (MathUtil.clamp(g, 0, 255) << 8) | MathUtil.clamp(b, 0, 255);
    }

    public static int rgb(int r, int g, int b) { return argb(255, r, g, b); }

    public static int alpha(int c) { return c >>> 24; }

    public static int red(int c) { return (c >> 16) & 0xFF; }

    public static int green(int c) { return (c >> 8) & 0xFF; }

    public static int blue(int c) { return c & 0xFF; }

    public static int withAlpha(int c, int alpha) { return (c & 0x00FFFFFF) | (MathUtil.clamp(alpha, 0, 255) << 24); }

    /** {r, g, b, a} as 0..1 floats (for GL). */
    public static float[] toFloats(int c) { return new float[]{ red(c) / 255f, green(c) / 255f, blue(c) / 255f, alpha(c) / 255f }; }

    /** From a to b, t = 0..1. */
    public static int blend(int a, int b, double t) {
        t = MathUtil.clamp(t, 0, 1);
        return argb((int) MathUtil.lerp(alpha(a), alpha(b), t), (int) MathUtil.lerp(red(a), red(b), t),
            (int) MathUtil.lerp(green(a), green(b), t), (int) MathUtil.lerp(blue(a), blue(b), t));
    }

    /** Darker by a factor 0..1 (0.7 is 30% darker). */
    public static int darker(int c, double factor) {
        return argb(alpha(c), (int) (red(c) * factor), (int) (green(c) * factor), (int) (blue(c) * factor));
    }

    public static int hsb(float hue, float saturation, float brightness) { return 0xFF000000 | Color.HSBtoRGB(hue, saturation, brightness); }

    /** A colour going round the colour wheel once per period; offset shifts it (to make waves along a list). */
    public static int rainbow(long periodMillis, long offsetMillis, float saturation, float brightness) {
        float hue = ((System.currentTimeMillis() + offsetMillis) % periodMillis) / (float) periodMillis;
        return hsb(hue, saturation, brightness);
    }

    /** The colour pulsing between itself and a darker shade. */
    public static int pulse(int c, long periodMillis, long offsetMillis) {
        double t = (Math.sin(((System.currentTimeMillis() + offsetMillis) % periodMillis) / (double) periodMillis * Math.PI * 2) + 1) / 2;
        return blend(c, darker(c, 0.5), t);
    }

    /** Green at full health through yellow to red. */
    public static int health(float health, float max) {
        float f = MathUtil.clamp(max <= 0 ? 0 : health / max, 0f, 1f);
        return hsb(f / 3f, 0.8f, 1f);
    }

    /** The colour of a Minecraft colour code ('c' for red...), or white. */
    public static int ofCode(char code) {
        int i = "0123456789abcdef".indexOf(Character.toLowerCase(code));
        if (i < 0) return 0xFFFFFFFF;
        int[] colors = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };
        return 0xFF000000 | colors[i];
    }
}
