package gale.util;

import java.util.concurrent.ThreadLocalRandom;

/** Numbers: clamping, angles, interpolation, randomness. */
public final class MathUtil {
    private MathUtil() { }

    public static double clamp(double v, double min, double max) { return v < min ? min : v > max ? max : v; }

    public static float clamp(float v, float min, float max) { return v < min ? min : v > max ? max : v; }

    public static int clamp(int v, int min, int max) { return v < min ? min : v > max ? max : v; }

    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Value between the last tick and this one for the current frame (prev + (cur - prev) * partialTicks). */
    public static double interpolate(double prev, double cur, double partialTicks) { return prev + (cur - prev) * partialTicks; }

    /** An angle in -180..180. */
    public static float wrap180(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    public static double wrap180(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    /** The shortest turn from a to b, -180..180. */
    public static float angleDiff(float a, float b) { return wrap180(b - a); }

    /** Moves current toward target by at most step. */
    public static double approach(double current, double target, double step) {
        return current < target ? Math.min(current + step, target) : Math.max(current - step, target);
    }

    public static float approach(float current, float target, float step) {
        return current < target ? Math.min(current + step, target) : Math.max(current - step, target);
    }

    public static double round(double v, int places) {
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }

    public static double sq(double v) { return v * v; }

    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(sq(x2 - x1) + sq(y2 - y1) + sq(z2 - z1));
    }

    // ------------------------------------------------------------------ random

    public static double random(double min, double max) { return min >= max ? min : ThreadLocalRandom.current().nextDouble(min, max); }

    /** A random int, both ends included. */
    public static int randomInt(int min, int max) { return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1); }

    /** True with the given chance in percent (0..100). */
    public static boolean chance(double percent) { return percent >= 100 || ThreadLocalRandom.current().nextDouble() * 100 < percent; }

    public static double gaussian(double mean, double deviation) { return mean + ThreadLocalRandom.current().nextGaussian() * deviation; }

    /** A gaussian value kept within min..max. */
    public static double gaussian(double mean, double deviation, double min, double max) { return clamp(gaussian(mean, deviation), min, max); }
}
