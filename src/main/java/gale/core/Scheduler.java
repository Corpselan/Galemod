package gale.core;

import gale.Gale;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs code later on the game thread: at the start of the next tick, after some ticks, or every few ticks.
 * {@link #main} may be called from any thread (packet listeners use it to get back onto the game thread).
 */
public final class Scheduler {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Queue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private static long tick;

    private Scheduler() { }

    /** Ticks since the game started (counts in menus too). */
    public static long ticks() { return tick; }

    /** Runs on the game thread at the start of the next tick. Safe from any thread. */
    public static void main(Runnable r) { MAIN.add(r); }

    /** Runs now if this is the game thread, else at the start of the next tick. */
    public static void now(Runnable r) {
        if (mc.isCallingFromMinecraftThread()) r.run();
        else MAIN.add(r);
    }

    /** Runs once, the given number of ticks from now (0: next tick). Game thread only. */
    public static Task later(int ticks, Runnable r) { return add(new Task(r, tick + Math.max(1, ticks), 0)); }

    /** Runs every few ticks until cancelled. Game thread only. */
    public static Task every(int ticks, Runnable r) { return add(new Task(r, tick + Math.max(1, ticks), Math.max(1, ticks))); }

    private static Task add(Task t) {
        synchronized (TASKS) { TASKS.add(t); }
        return t;
    }

    /** Called by the hooks at the start of every tick. */
    static void tick() {
        tick++;
        Runnable r;
        while ((r = MAIN.poll()) != null) run(r);
        List<Task> due = new ArrayList<>();
        synchronized (TASKS) {
            TASKS.removeIf(t -> t.cancelled);
            for (Task t : TASKS) if (t.at <= tick) due.add(t);
        }
        for (Task t : due) {
            if (t.cancelled) continue;
            run(t.action);
            if (t.every > 0) t.at = tick + t.every;
            else t.cancelled = true;
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Gale.LOG.error("Scheduled task threw", t);
        }
    }

    public static final class Task {
        final Runnable action;
        long at;
        final int every;
        volatile boolean cancelled;

        Task(Runnable action, long at, int every) {
            this.action = action;
            this.at = at;
            this.every = every;
        }

        public void cancel() { cancelled = true; }

        public boolean isCancelled() { return cancelled; }
    }
}
