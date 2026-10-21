package gale.bridge;

import gale.Gale;
import gale.event.impl.AntiCheatFlagEvent;
import gale.event.impl.BedWarsAlertEvent;
import gale.event.impl.IrcMessageEvent;
import gale.event.impl.LocrawEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;

/**
 * What B4 itself knows, as the GaleLink script reports it every tick (through Gale's script API, b4.report /
 * b4.event): its KillAura, AimAssist, Backtrack and Lag Range targets, BedAura's bed and progress, whether its
 * Scaffold / Tower are placing, its Bed Wars bed; and its Bed Wars alerts, AntiCheat flags, /locraw answers and IRC
 * messages, which become Gale events. Nothing here when B4 (or GaleLink) is not running: a report older than a second
 * counts as none.
 */
public final class B4State {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static volatile long reportedAt;
    private static volatile int killAura = -1, aimAssist = -1, backtrack = -1, lagRange = -1;
    private static volatile BlockPos bedAuraPos, ownBed;
    private static volatile float bedAuraProgress, bedAuraVanilla;
    private static volatile boolean scaffolding, towering;

    private B4State() { }

    /** GaleLink reported within the last second. */
    public static boolean fresh() { return reportedAt != 0 && System.currentTimeMillis() - reportedAt < 1000; }

    // ------------------------------------------------------------------ from GaleLink

    /** b4.report: targets, BedAura, Scaffold / Tower, own bed. */
    public static void report(Entity killAura, Entity aimAssist, Entity backtrack, Entity lagRange, BlockPos bedAuraPos, float[] bedAura,
                              boolean scaffolding, boolean towering, BlockPos ownBed) {
        B4State.killAura = id(killAura);
        B4State.aimAssist = id(aimAssist);
        B4State.backtrack = id(backtrack);
        B4State.lagRange = id(lagRange);
        B4State.bedAuraPos = bedAuraPos;
        B4State.bedAuraProgress = bedAura != null && bedAura.length > 0 ? bedAura[0] : 0f;
        B4State.bedAuraVanilla = bedAura != null && bedAura.length > 1 ? bedAura[1] : 0f;
        B4State.scaffolding = scaffolding;
        B4State.towering = towering;
        B4State.ownBed = ownBed;
        reportedAt = System.currentTimeMillis();
    }

    /** b4.event: one of B4's events, as a Gale event. */
    public static void event(String kind, String text, Entity player) {
        EntityPlayer p = player instanceof EntityPlayer ? (EntityPlayer) player : null;
        switch (kind) {
            case "bedwars":
                if (p != null) Gale.BUS.post(new BedWarsAlertEvent(text, p, true));
                break;
            case "anticheat":
                if (p != null) Gale.BUS.post(new AntiCheatFlagEvent(text, p, true));
                break;
            case "locraw":
                Gale.BUS.post(new LocrawEvent(text, true));
                break;
            case "irc":
                Gale.BUS.post(new IrcMessageEvent(text));
                break;
            default:
        }
    }

    private static int id(Entity e) { return e == null ? -1 : e.getEntityId(); }

    private static Entity entity(int id) {
        if (id < 0 || !fresh() || mc.theWorld == null) return null;
        return mc.theWorld.getEntityByID(id);
    }

    // ------------------------------------------------------------------ for Gale

    public static Entity killAuraTarget() { return entity(killAura); }

    public static Entity aimAssistTarget() { return entity(aimAssist); }

    public static Entity backtrackTarget() { return entity(backtrack); }

    public static Entity lagRangeTarget() { return entity(lagRange); }

    public static BlockPos bedAuraPosition() { return fresh() ? bedAuraPos : null; }

    /** {B4's progress, the vanilla progress}, zeros when it is not breaking. */
    public static float[] bedAuraProgress() { return fresh() ? new float[]{ bedAuraProgress, bedAuraVanilla } : new float[]{ 0f, 0f }; }

    public static boolean scaffolding() { return fresh() && scaffolding; }

    public static boolean towering() { return fresh() && towering; }

    public static BlockPos ownBed() { return fresh() ? ownBed : null; }
}
