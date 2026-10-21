package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;


public class MouseInputEvent extends Event {
    private final Action action;

    public MouseInputEvent(Action action) {
        this.action = action;
    }

    public Action getAction() { return action; }

    public enum Action { RIGHT_CLICK, LEFT_CLICK, MIDDLE_CLICK }
}
