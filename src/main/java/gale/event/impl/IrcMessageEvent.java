package gale.event.impl;

import gale.event.Event;

/** A message from B4's IRC (its own chat between B4 users), forwarded by GaleLink; Gale has no IRC of its own. */
public final class IrcMessageEvent extends Event {
    public final String message;

    public IrcMessageEvent(String message) { this.message = message; }
}
