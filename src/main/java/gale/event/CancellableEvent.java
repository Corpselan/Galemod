package gale.event;

/**
 * An event a listener can cancel. Every listener still receives it (in priority order) and can check
 * {@link #isCancelled()}; whoever fired it decides what cancelling means (drop the packet, keep the click from happening...).
 */
public abstract class CancellableEvent extends Event {
    private boolean cancelled;

    public void cancel() { cancelled = true; }

    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    public boolean isCancelled() { return cancelled; }
}
