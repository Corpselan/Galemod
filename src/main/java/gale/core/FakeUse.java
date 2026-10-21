package gale.core;

import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Field;

/**
 * Makes the game think you are using your held item (blocking, for a sword) on your side only: nothing is sent.
 * <ul>
 * <li>{@link Mode#RENDER}: only while frames are drawn: the block animation in first and third person.</li>
 * <li>{@link Mode#FULL}: also while you move: the game slows you as when really blocking, and other code that asks
 * "is the player using an item" during your update (other clients' NoSlow, "disable while blocking"...) sees yes.</li>
 * </ul>
 * The game lets go of an item when right click is up, with a C07; so the state is taken out before that check at the
 * start of every tick and put back after it. Clicks work as usual (attacking while the animation shows). When you
 * really use an item, that one wins.
 */
public final class FakeUse {
    public enum Mode { OFF, RENDER, FULL }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field ITEM = ReflectUtil.field(EntityPlayer.class, "itemInUse", "field_71074_e");
    private static final Field COUNT = ReflectUtil.field(EntityPlayer.class, "itemInUseCount", "field_71072_f");
    private static volatile Mode mode = Mode.OFF;
    /** the stack Gale put in, while it is in */
    private static ItemStack applied;

    private FakeUse() { }

    public static Mode mode() { return mode; }

    public static void set(Mode m) {
        mode = m == null ? Mode.OFF : m;
        if (mode == Mode.OFF && mc.isCallingFromMinecraftThread()) take();
    }

    /** Whether the using state the game sees right now is Gale's. */
    public static boolean isApplied() {
        return applied != null && mc.thePlayer != null && mc.thePlayer.getItemInUse() == applied;
    }

    /** Tick start: out before the game's "right click is up, let go" check. */
    static void tickStart() { take(); }

    /** Your update: back in for FULL, before the game reads your movement. */
    static void update() { if (mode == Mode.FULL) put(); }

    static void frameStart() { if (mode != Mode.OFF) put(); }

    static void frameEnd() { if (mode == Mode.RENDER) take(); }

    private static void put() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) return;
        if (applied != null && p.getItemInUse() == applied) return;
        applied = null;
        if (p.isUsingItem()) return;
        ItemStack held = p.inventory.getCurrentItem();
        if (held == null) return;
        int duration = held.getMaxItemUseDuration();
        ReflectUtil.set(ITEM, p, held);
        ReflectUtil.set(COUNT, p, duration > 0 ? duration : 72000);
        applied = held;
    }

    private static void take() {
        EntityPlayerSP p = mc.thePlayer;
        if (applied != null && p != null && p.getItemInUse() == applied) {
            ReflectUtil.set(ITEM, p, null);
            ReflectUtil.set(COUNT, p, 0);
        }
        applied = null;
    }
}
