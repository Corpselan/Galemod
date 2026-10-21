package gale.util;

/** Measures time since the last reset, in milliseconds. */
public final class Stopwatch {
    private long start = System.currentTimeMillis();

    public void reset() { start = System.currentTimeMillis(); }

    public long elapsed() { return System.currentTimeMillis() - start; }

    public boolean passed(long millis) { return elapsed() >= millis; }

    /** True (and resets) when the time has passed. */
    public boolean passedAndReset(long millis) {
        if (!passed(millis)) return false;
        reset();
        return true;
    }

    /** Sets it as if it was reset this long ago. */
    public void setElapsed(long millis) { start = System.currentTimeMillis() - millis; }
}
