package gale.event;

/** Listener priorities: higher runs first. Any int works; these are the usual steps. */
public final class Priority {
    public static final int HIGHEST = 200;
    public static final int HIGH = 100;
    public static final int NORMAL = 0;
    public static final int LOW = -100;
    public static final int LOWEST = -200;
    /** last of all, to look at the final result without changing it */
    public static final int MONITOR = -1000;

    private Priority() { }
}
