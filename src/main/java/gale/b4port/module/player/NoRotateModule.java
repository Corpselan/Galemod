package gale.b4port.module.player;

/** From B4's NoRotate: when the server last teleported you (B4Port notes every S08; a setback is one that turns you). */
public final class NoRotateModule {
    public static volatile long lastS08;
    public static volatile long lastSetback;

    private NoRotateModule() {
    }

    public static boolean sinceS08(long ms) {
        return System.currentTimeMillis() - lastS08 < ms;
    }

    public static boolean sinceSetback(long ms) {
        return System.currentTimeMillis() - lastSetback < ms;
    }

    public static void onS08(float pitch) {
        lastS08 = System.currentTimeMillis();
        if (pitch != 0.0f) {
            lastSetback = lastS08;
        }
    }
}
