package gale.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Silent hotbar switching, the way RotationManager does rotations. A module asks for a slot in
 * {@link gale.event.impl.UpdateEvent} ({@link #request}); from then on, during each tick the player holds that slot
 * (placing and hitting use its item, and the server is told once), while between ticks, when the hotbar and the hand
 * are drawn, your own slot is back. Ask every tick to keep it; when nobody asks, your slot comes back for the server too.
 * <p>Changing slot yourself (number keys, the wheel) while it is held makes that your own slot.
 */
public final class SlotManager {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static Object owner;
    private static int wantSlot = -1, wantPriority = Integer.MIN_VALUE;

    private static boolean held;
    private static int heldSlot = -1, ownSlot = -1;
    private static EntityPlayerSP heldPlayer;
    private static Object activeOwner;

    private SlotManager() { }

    /** Asks to hold a hotbar slot (0..8) this tick (call in UpdateEvent). The highest priority wins. */
    public static void request(Object owner, int slot, int priority) {
        if (slot < 0 || slot > 8 || priority <= wantPriority) return;
        SlotManager.owner = owner;
        wantSlot = slot;
        wantPriority = priority;
    }

    /** Whether a slot is being held for a module. */
    public static boolean isActive() { return held; }

    public static Object owner() { return activeOwner; }

    /** Your own slot (what the hotbar shows), also while another one is held. */
    public static int ownSlot() {
        if (held) return ownSlot;
        return mc.thePlayer == null ? 0 : mc.thePlayer.inventory.currentItem;
    }

    // ------------------------------------------------------------------ called by the hooks

    /** Tick start, before the game tells the server the held slot: put the held slot back in place. */
    static void startTick() {
        EntityPlayerSP p = mc.thePlayer;
        if (!held || p == null || p != heldPlayer) {
            held = false;
            return;
        }
        ownSlot = p.inventory.currentItem;
        p.inventory.currentItem = heldSlot;
    }

    static void beginUpdate() {
        owner = null;
        wantSlot = -1;
        wantPriority = Integer.MIN_VALUE;
    }

    /** After UpdateEvent: hold what was asked for, or let go. */
    static void apply() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) return;
        if (wantSlot < 0) {
            if (held) {
                p.inventory.currentItem = ownSlot;
                held = false;
                activeOwner = null;
            }
            return;
        }
        if (!held) ownSlot = p.inventory.currentItem;
        else if (p.inventory.currentItem != heldSlot) ownSlot = p.inventory.currentItem; // changed by hand this tick
        heldSlot = wantSlot;
        heldPlayer = p;
        activeOwner = owner;
        p.inventory.currentItem = heldSlot;
        held = true;
    }

    /** Tick end: show your own slot until the next tick. */
    static void endTick() {
        EntityPlayerSP p = mc.thePlayer;
        if (!held || p == null || p != heldPlayer) return;
        if (p.inventory.currentItem != heldSlot) {
            // changed by hand during the tick: that is the new own slot, and nothing is held any more
            held = false;
            activeOwner = null;
            return;
        }
        p.inventory.currentItem = ownSlot;
    }
}
