package gale.core;

import gale.Gale;
import gale.event.impl.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wraps the player's movement input (no mixin) while Gale needs it: something listens to {@link InputEvent}, or a
 * silent rotation turns the keys (movement fix). Otherwise the game's own input is left alone, and put back if Gale
 * had wrapped it.
 * <p>The game asks the wrapper for the keys once a tick; the wrapper lets the wrapped input run <b>as the player's own
 * input</b>, so hooks inside it (another client's "after the keys were read", e.g. B4's onPostPlayerInput, and its
 * scripts' client.getForward / setForward) read and change that input and their changes are kept. Then it fires
 * {@link InputEvent} and turns the result for the silent rotation.
 * <p>If something replaces it, it is wrapped again on the next tick; if another client wraps it, it keeps working inside.
 */
public final class InputHook extends MovementInputFromOptions {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** the last tick each player's hook ran (a new player after respawn gets its own) */
    private static final Map<EntityPlayerSP, Long> LAST_RUN = new WeakHashMap<>();
    private final MovementInput inner;
    private final EntityPlayerSP owner;

    private InputHook(MovementInput inner, EntityPlayerSP owner) {
        super(mc.gameSettings);
        this.inner = inner;
        this.owner = owner;
    }

    /** Whether Gale needs to see or change the movement keys. */
    static boolean needed() {
        return Gale.BUS.hasListeners(InputEvent.class) || RotationManager.isActive();
    }

    /** Wraps the current movement input when Gale needs it (unless this player's hook ran last tick), unwraps it when not. */
    static void ensure(EntityPlayerSP p) {
        MovementInput current = p.movementInput;
        if (current == null) return;
        boolean needed = needed();
        if (current instanceof InputHook) {
            if (!needed) p.movementInput = ((InputHook) current).inner;
            return;
        }
        if (!needed) return;
        Long last = LAST_RUN.get(p);
        if (last != null && Scheduler.ticks() - last <= 1) return;
        p.movementInput = new InputHook(current, p);
    }

    /** The movement input as the game read it, before Gale's listeners changed it. */
    public MovementInput original() { return inner; }

    @Override
    public void updatePlayerMoveState() {
        boolean own = owner.movementInput == this;
        if (own) owner.movementInput = inner;
        try {
            inner.updatePlayerMoveState();
        } finally {
            if (own && owner.movementInput == inner) owner.movementInput = this;
        }
        InputEvent ev = new InputEvent(inner.moveForward, inner.moveStrafe, inner.jump, inner.sneak);
        boolean current = mc.thePlayer == owner;
        if (current) {
            LAST_RUN.put(owner, Scheduler.ticks());
            Gale.BUS.post(ev);
        }
        float[] fixed = current ? RotationManager.fixInput(ev.forward, ev.strafe) : new float[]{ ev.forward, ev.strafe };
        moveForward = fixed[0];
        moveStrafe = fixed[1];
        jump = ev.jump;
        sneak = ev.sneak;
        if (current && ev.sprint != null) {
            if (!ev.sprint) owner.setSprinting(false);
            else if (moveForward >= 0.8f && !sneak && !owner.isUsingItem() && !owner.isCollidedHorizontally
                && (owner.getFoodStats().getFoodLevel() > 6 || owner.capabilities.allowFlying)) owner.setSprinting(true);
        }
    }
}
