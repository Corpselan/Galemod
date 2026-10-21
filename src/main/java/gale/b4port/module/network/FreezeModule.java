package gale.b4port.module.network;

/** B4's Freeze is not ported (never frozen). */
public final class FreezeModule {
    public static volatile boolean frozen;

    private FreezeModule() {
    }
}
