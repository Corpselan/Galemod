package gale.event.impl;

import gale.event.Event;

/**
 * Hypixel answered /locraw: where you are, as its JSON line ({"server":"mini123A","gametype":"BEDWARS","mode":...}).
 * From Gale's own /locraw (gale.core.Locraw) or forwarded from B4 ({@link #fromB4}). Game thread.
 */
public final class LocrawEvent extends Event {
    public final String json;
    public final boolean fromB4;

    public LocrawEvent(String json, boolean fromB4) {
        this.json = json;
        this.fromB4 = fromB4;
    }
}
