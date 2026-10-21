package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;

/** Meowtils changes text as the font draws it; Gale never posts this (extensions that listen for it still load). */
public class RenderStringEvent extends Event {
    private String string;

    public RenderStringEvent(String string) { this.string = string; }

    public String getString() { return string; }

    public void setString(String string) { this.string = string; }
}
