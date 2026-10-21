package gale.util;

/**
 * Times clicks for a CPS range, with a human-like spread: each gap is drawn around the mean of the range, sometimes a
 * little longer. Call {@link #due} every frame; it says how many clicks to make now (usually 0 or 1).
 */
public final class Clicker {
    private long next;

    /** Clicks due now for a CPS between min and max. */
    public int due(double minCps, double maxCps) {
        long now = System.currentTimeMillis();
        if (next == 0 || now - next > 1000) next = now;
        int clicks = 0;
        while (now >= next && clicks < 3) {
            clicks++;
            next += gap(minCps, maxCps);
        }
        return clicks;
    }

    /** Start again (the next call clicks at once). */
    public void reset() { next = 0; }

    /** One gap between clicks in milliseconds. */
    public static long gap(double minCps, double maxCps) {
        double lo = Math.max(0.5, Math.min(minCps, maxCps)), hi = Math.max(lo, Math.max(minCps, maxCps));
        double mean = 1000.0 / ((lo + hi) / 2);
        double spread = (1000.0 / lo - 1000.0 / hi) / 4 + mean * 0.08;
        double gap = MathUtil.gaussian(mean, spread, 1000.0 / hi * 0.85, 1000.0 / lo * 1.15);
        if (MathUtil.chance(4)) gap += MathUtil.random(20, 70);
        return Math.max(25, Math.round(gap));
    }
}
