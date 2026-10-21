package gale.b4port.module.other;

/** From B4's Latency Alerts: when the last packet came in (B4Port keeps it). */
public final class LatencyAlertsModule {
    public static volatile long lastPacket;

    private LatencyAlertsModule() {
    }
}
