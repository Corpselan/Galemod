package gale.event.impl;

import gale.event.Event;

/** Connected to a server (after the handshake) or disconnected. Game thread. */
public abstract class ServerEvent extends Event {
    public static final class Connect extends ServerEvent {
        public final String address;

        public Connect(String address) { this.address = address; }
    }

    public static final class Disconnect extends ServerEvent {
    }
}
