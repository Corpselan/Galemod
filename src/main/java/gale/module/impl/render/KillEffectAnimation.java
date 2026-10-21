package gale.module.impl.render;

/** A clock-driven, allocation-free two-layer animation. No Minecraft or GL dependencies. */
public final class KillEffectAnimation {
    private int current, previous;
    private long started;
    private float entryScale, oldScale, oldAlpha;

    public void show(int stage, long now, double durationMs) {
        boolean visible = visible(now, durationMs);
        boolean incomingDominates = visible && incomingAlpha(now, durationMs) >= outgoingAlpha(now, durationMs);
        float scale = visible ? (incomingDominates ? scale(now) : outgoingScale(now)) : 1f;
        // Keep the dominant layer when interrupted; never accumulate more than two textures.
        previous = visible ? (incomingDominates ? current : previous) : 0;
        oldScale = scale;
        oldAlpha = visible ? Math.max(incomingAlpha(now, durationMs), outgoingAlpha(now, durationMs)) : 0f;
        entryScale = visible ? Math.max(0.6f, scale * 0.84f) : 0.42f;
        current = Math.max(1, Math.min(6, stage));
        started = now;
    }

    public void clear() { current = previous = 0; }

    public int current() { return current; }

    public int previous() { return previous; }

    private float age(long now) { return Math.max(0f, (now - started) / 1_000_000f); }

    public boolean visible(long now, double durationMs) { return current != 0 && age(now) < durationMs; }

    public float scale(long now) {
        float t = clamp(age(now) / 280f) - 1f;
        // easeOutBack: a fast entrance with a small, damped overshoot.
        float ease = 1f + 2.4f * t * t * t + 1.4f * t * t;
        return entryScale + (1f - entryScale) * ease;
    }

    public float outgoingScale(long now) { return oldScale * (1f + 0.08f * smooth(age(now) / 180f)); }

    public float incomingAlpha(long now, double durationMs) {
        return smooth(age(now) / 150f) * fade(now, durationMs);
    }

    public float outgoingAlpha(long now, double durationMs) {
        return previous == 0 ? 0f : oldAlpha * (1f - smooth(age(now) / 180f)) * fade(now, durationMs);
    }

    public float exitScale(long now, double durationMs) {
        return 1f - 0.06f * smooth((age(now) - (float) durationMs + 280f) / 280f);
    }

    private float fade(long now, double durationMs) { return 1f - smooth((age(now) - (float) durationMs + 280f) / 280f); }

    private static float smooth(float t) { t = clamp(t); return t * t * (3f - 2f * t); }

    private static float clamp(float t) { return Math.max(0f, Math.min(1f, t)); }
}
