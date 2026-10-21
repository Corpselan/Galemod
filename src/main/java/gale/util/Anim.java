package gale.util;

/**
 * Animation by real time, so it looks the same at 30 or 300 frames a second. {@link #approach} eases a value toward
 * its target (fast at first, gently at the end, never past it); the easing curves shape a 0..1 progress.
 */
public final class Anim {
    private Anim() { }

    /**
     * Moves current toward target by the share of the way that speed covers in dt seconds (1 - e^(-speed·dt)): with
     * speed 10 it is 63% of the way in 0.1 s, 95% in 0.3 s. Snaps to the target once within a hair of it.
     */
    public static float approach(float current, float target, float speed, float dt) {
        if (dt <= 0f || current == target) return current;
        float v = current + (target - current) * (float) (1.0 - Math.exp(-speed * dt));
        return Math.abs(target - v) < 0.002f * Math.max(1f, Math.abs(target)) ? target : v;
    }

    public static float clamp01(float t) { return t < 0f ? 0f : t > 1f ? 1f : t; }

    public static float easeOutCubic(float t) {
        t = 1f - clamp01(t);
        return 1f - t * t * t;
    }

    public static float easeInCubic(float t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    public static float easeOutExpo(float t) {
        t = clamp01(t);
        return t >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * t);
    }

    /** A little past the end and back: for things popping in. */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** Seconds between calls (the frame time of whatever uses it), at most 0.1 s so a stall does not make a jump. */
    public static final class Clock {
        private long last = -1L;

        public float tick() {
            long now = System.nanoTime();
            float dt = last < 0L ? 0f : Math.min(0.1f, (now - last) / 1.0e9f);
            last = now;
            return dt;
        }
    }
}
