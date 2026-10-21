package gale.meowtils.api.event.api;

public abstract class Event {
    private boolean cancelled;

    public boolean isCancelled() { return cancelled; }

    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
