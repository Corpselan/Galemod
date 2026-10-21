package gale.b4port.client;

/** B4's IRC (chat between B4 users): Gale has none; B4's own IRC messages reach Gale through GaleLink. */
public final class Irc {
    private Irc() {
    }

    public static void onKillAuraHit(Object... ignored) {
    }

    public static void onScaffoldPlace(Object... ignored) {
    }

    public static void trackFps() {
    }

    public static boolean onChat(String s) {
        return false;
    }
}
