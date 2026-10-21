package gale.kill.gd.client.render.effect;

import com.google.gson.JsonObject;

/** GD motion with optional per-skin timings. Missing keys preserve the upstream animation. */
public final class ValorantMotion {
    private final float entryY, entryDuration, bounceDistance, bounceStart, bouncePeak, bounceHold, bounceEnd;
    private final float spinStart, spin180, spin360, angle180, angle360, fade, fiveFade;
    private final float frameDelay, frameDuration, frameInitial, emblemDuration, emblemInitial;
    private final long duration;
    private final boolean foxFan;
    private final float emblemPeak, emblemPeakTime, barDelay, barOrientationScale, barSixOrientationScale, barSixRadiusOffset, barSixOrientationOrigin;
    // Ayakashi six-kill crest: three tails on each side; no top-center tail.
    private static final float[][] FOX_FAN = {
        {0}, {-28, 28}, {0, -55, 55}, {-28, 28, -80, 80}, {0, -40, 40, -80, 80}, {-55, 55, -90, 90, -125, 125}
    };

    public ValorantMotion(JsonObject c) {
        entryY = number(c, "icon_entry_offset_y", -16);
        entryDuration = millis(c, "icon_entry_duration", .1f);
        bounceDistance = number(c, "bar_bounce_distance", 9);
        bounceStart = millis(c, "bar_bounce_start", .02f);
        bouncePeak = Math.max(bounceStart + 1, millis(c, "bar_bounce_peak", .18f));
        bounceHold = Math.max(bouncePeak, millis(c, "bar_bounce_hold", .27f));
        bounceEnd = Math.max(bounceHold + 1, millis(c, "bar_bounce_end", .62f));
        spinStart = millis(c, "bar_spin_start", .75f);
        spin180 = Math.max(1, millis(c, "bar_spin_duration", .7f));
        spin360 = Math.max(1, millis(c, "bar_spin_duration_five", 1));
        angle180 = number(c, "bar_spin_degrees", 180);
        angle360 = number(c, "bar_spin_degrees_five", 360);
        fade = Math.max(1, millis(c, "icon_fade_duration", .22f));
        fiveFade = millis(c, "five_plus_fade_in_duration", .3f);
        duration = Math.max(300, Math.round(millis(c, "icon_display_duration", 2.6f)));
        frameDelay = millis(c, "frame_entry_delay", 0);
        frameDuration = millis(c, "frame_entry_duration", 0);
        frameInitial = Math.max(.05f, number(c, "frame_entry_initial_scale", 1));
        emblemDuration = millis(c, "emblem_entry_duration", 0);
        emblemInitial = Math.max(.05f, number(c, "emblem_entry_initial_scale", 1));
        emblemPeak = Math.max(.05f, number(c, "emblem_entry_peak_scale", emblemInitial));
        emblemPeakTime = Math.min(Math.max(0, emblemDuration - 1), millis(c, "emblem_entry_peak_time", 0));
        barDelay = millis(c, "bar_entry_delay", 0);
        barOrientationScale = number(c, "bar_orientation_scale", 1);
        barSixOrientationScale = number(c, "bar_six_orientation_scale", barOrientationScale);
        barSixRadiusOffset = number(c, "bar_six_radius_offset", Float.NaN);
        barSixOrientationOrigin = number(c, "bar_six_orientation_origin", 0);
        foxFan = c != null && c.has("bar_layout") && "fox_fan".equals(c.get("bar_layout").getAsString());
    }

    public long duration() { return duration; }
    public float entryY(long elapsed) {
        return entryDuration <= 0 || elapsed >= entryDuration ? 0 : entryY * (1 - cubic(elapsed / entryDuration));
    }
    public float alpha(long elapsed, long totalDuration, int combo) {
        float alpha = combo >= 5 && fiveFade > 0 ? quad(elapsed / fiveFade) : 1;
        if (totalDuration > fade && elapsed > totalDuration - fade)
            alpha *= 1 - clamp((elapsed - (totalDuration - fade)) / fade);
        return alpha;
    }
    public float barTravel(long elapsed) {
        if (elapsed < bounceStart || elapsed >= bounceEnd) return 0;
        if (elapsed <= bouncePeak) {
            float p = (elapsed - bounceStart) / (bouncePeak - bounceStart);
            return bounceDistance * (quad(p) * .8f + cubic(p) * .2f);
        }
        if (elapsed <= bounceHold) return bounceDistance;
        float p = clamp((elapsed - bounceHold) / (bounceEnd - bounceHold));
        return bounceDistance * (1 - p*p*p*p);
    }
    public float barRotation(long elapsed, int combo, float direction) {
        if (combo <= 1 || elapsed <= spinStart) return 0;
        return (combo >= 5 ? angle360 : angle180) * direction
            * cubic((elapsed - spinStart) / (combo >= 5 ? spin360 : spin180));
    }
    public float frameAlpha(long elapsed) {
        if (elapsed < frameDelay) return 0;
        return frameDuration <= 0 ? 1 : cubic((elapsed - frameDelay) / frameDuration);
    }
    public float frameScale(long elapsed) {
        float p = frameDuration <= 0 ? 1 : cubic((elapsed - frameDelay) / frameDuration);
        return frameInitial + (1 - frameInitial) * p;
    }
    public float emblemScale(long elapsed) {
        if (emblemPeakTime > 0) {
            if (elapsed <= emblemPeakTime)
                return emblemInitial + (emblemPeak - emblemInitial) * cubic(elapsed / emblemPeakTime);
            return emblemPeak + (1 - emblemPeak) * cubic((elapsed - emblemPeakTime) / (emblemDuration - emblemPeakTime));
        }
        float p = emblemDuration <= 0 ? 1 : cubic(elapsed / emblemDuration);
        return emblemInitial + (1 - emblemInitial) * p;
    }
    public boolean usesFoxFan() { return foxFan; }
    public float barOrientationOffset(float layoutAngle) { return barOrientationOffset(layoutAngle, 1); }
    public float barOrientationOffset(float layoutAngle, int combo) {
        float scale = foxFan && combo >= 6 ? barSixOrientationScale : barOrientationScale;
        float origin = foxFan && combo >= 6 ? Math.signum(layoutAngle) * barSixOrientationOrigin : 0;
        return (layoutAngle - origin) * scale - layoutAngle;
    }
    public float barRadiusOffset(int combo, float fallback) {
        return foxFan && combo >= 6 && !Float.isNaN(barSixRadiusOffset) ? barSixRadiusOffset : fallback;
    }
    public float[] barLayout(int combo, float[] fallback) {
        return foxFan ? FOX_FAN[Math.max(0, Math.min(5, combo - 1))] : fallback;
    }
    public long barElapsed(long elapsed) { return Math.max(0, elapsed - Math.round(barDelay)); }
    public float barAlpha(long elapsed) {
        return barDelay <= 0 ? 1 : cubic((elapsed - barDelay) / 100f);
    }
    private static float number(JsonObject c, String key, float fallback) {
        return c != null && c.has(key) ? c.get(key).getAsFloat() : fallback;
    }
    private static float millis(JsonObject c, String key, float fallback) {
        return Math.max(0, Math.round(number(c, key, fallback) * 1000));
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private static float cubic(float p) { float v = 1-clamp(p); return 1-v*v*v; }
    private static float quad(float p) { float v = 1-clamp(p); return 1-v*v; }
}
