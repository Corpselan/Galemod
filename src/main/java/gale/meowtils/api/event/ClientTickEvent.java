package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;


public class ClientTickEvent extends Event {
    private final Phase phase;

    public ClientTickEvent(Phase phase) {
        this.phase = phase;
    }

    public Phase getPhase() { return phase; }

    public enum Phase { PRE, POST }
}
