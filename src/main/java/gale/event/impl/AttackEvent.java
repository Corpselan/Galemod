package gale.event.impl;

import gale.event.CancellableEvent;
import net.minecraft.entity.Entity;

/**
 * You attack something: a click, a module, another client's KillAura, a script's {@code client.attack}. Game thread.
 * The C02 has already gone out; this is about your side of the hit, which comes next.
 * <ul>
 * <li>Cancel: none of your side happens (no slowdown, no sprint stop, no crit particles, no durability loss).</li>
 * <li>{@link #slowdown} / {@link #keepSprint}: a hit that knocks back (sprinting or Knockback enchant, on a player)
 * multiplies your horizontal motion by {@code slowdown} (vanilla 0.6) and stops your sprint unless {@code keepSprint}.
 * Changing either makes Gale do the hit itself instead of the game (same steps, these two values).</li>
 * </ul>
 */
public final class AttackEvent extends CancellableEvent {
    public static final double VANILLA_SLOWDOWN = 0.6;

    public final Entity target;
    public double slowdown = VANILLA_SLOWDOWN;
    public boolean keepSprint;

    public AttackEvent(Entity target) { this.target = target; }

    /** Whether a listener changed the slowdown or the sprint stop. */
    public boolean isModified() { return slowdown != VANILLA_SLOWDOWN || keepSprint; }
}
