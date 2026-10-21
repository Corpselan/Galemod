package meowtils.furiahvisuals.module.watermark;

import meowtils.furiahvisuals.core.FuriahsVisuals;
import meowtils.furiahvisuals.core.ModuleManager;
import meowtils.furiahvisuals.module.Module;
import meowtils.furiahvisuals.setting.BooleanSetting;
import meowtils.furiahvisuals.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * Bed Wars on the watermark island: an enemy near your bed (the nearest, how far, how many), who is breaking it (the
 * block and how far along), and when it is gone. Your bed and team come from Gale's Bed Wars tracking ({@link GaleBeds});
 * the messages are the island's own, kept up while the thing lasts and updated in place. Minecraft names are SRG.
 */
public final class BedTrackerModule extends Module {
    private final SliderSetting range;
    private final BooleanSetting teammates, digging, destroyed;
    private boolean broken, wasDestroyed;
    private String shown = "";
    private long shownAt, digAt;
    private int digId = -1, digStage, digX, digY, digZ;

    private BedTrackerModule() {
        super("bedtracker", "Bed Tracker", "Bed Wars, on the island: an enemy near your bed, who is breaking it and how far along, and when it is gone.", true);
        this.range = this.add(new SliderSetting("range", "Detect Range", "How near your bed counts as near.", 5.0, 60.0, 25.0, 1.0).unit(" blocks"));
        this.teammates = this.add(new BooleanSetting("teammates", "Teammates Too", "Also tell when a teammate is near your bed.", false));
        this.digging = this.add(new BooleanSetting("digging", "Who Is Breaking It", "Who is breaking your bed, the block and how far along (when they are within about 30 blocks of you).", true));
        this.destroyed = this.add(new BooleanSetting("destroyed", "Bed Destroyed", "Tell when your bed is gone.", true));
    }

    /** Registers it (called from FuriahsVisuals.registerModules); without Gale it is simply left out. */
    public static void addTo(ModuleManager modules) {
        try {
            modules.register(new BedTrackerModule());
        } catch (Throwable t) {
            FuriahsVisuals.LOG.warn("Bed Tracker left out: {}", new Object[]{ t.toString() });
        }
    }

    @Override
    public void onTick() {
        if (this.broken || !this.isEnabled()) return;
        try {
            GaleBeds.listen();
            this.track();
        } catch (LinkageError e) {
            this.broken = true;
            FuriahsVisuals.LOG.warn("Bed Tracker needs Gale; it stays off ({})", new Object[]{ e.toString() });
        } catch (RuntimeException e) {
            FuriahsVisuals.LOG.error("Bed Tracker failed", e);
        }
    }

    private void track() {
        Minecraft mc = Minecraft.func_71410_x();
        World world = mc.field_71441_e;
        long now = System.currentTimeMillis();
        double[] bed = GaleBeds.inGame() ? GaleBeds.bed() : null;
        if (world == null || mc.field_71439_g == null || bed == null) {
            GaleBeds.BREAKS.clear();
            this.wasDestroyed = false;
            this.digId = -1;
            return;
        }
        // the bed gone: once
        boolean gone = GaleBeds.destroyed();
        if (gone && !this.wasDestroyed && this.destroyed.get()) {
            String detail = GaleBeds.destroyedDetail();
            this.post("Your bed was destroyed" + (detail.isEmpty() ? "" : "  ·  " + detail), IslandIcons.Icon.SKULL, now, true);
        }
        this.wasDestroyed = gone;
        if (gone) {
            GaleBeds.BREAKS.clear();
            return;
        }
        // someone breaking a block at the bed (the bed or its defence)
        for (int[] b; (b = GaleBeds.BREAKS.poll()) != null; ) {
            double dx = b[1] + 0.5 - bed[0], dy = b[2] + 0.5 - bed[1], dz = b[3] + 0.5 - bed[2];
            if (dx * dx + dy * dy + dz * dz > 5.0 * 5.0) continue;
            if (b[4] < 0 || b[4] > 9) {
                if (b[0] == this.digId) this.digId = -1;
                continue;
            }
            this.digId = b[0];
            this.digStage = b[4];
            this.digX = b[1];
            this.digY = b[2];
            this.digZ = b[3];
            this.digAt = now;
        }
        if (this.digId != -1 && now - this.digAt > 1500) this.digId = -1;
        if (this.digging.get() && this.digId != -1) {
            Entity who = world.func_73045_a(this.digId);
            String name = who == null ? "Someone" : who.func_70005_c_();
            String block = world.func_180495_p(new BlockPos(this.digX, this.digY, this.digZ)).func_177230_c().func_149732_F();
            // the scene (them swinging at the cracking block) is drawn in the room the leading spaces leave
            BedArt.entity = this.digId;
            BedArt.x = this.digX;
            BedArt.y = this.digY;
            BedArt.z = this.digZ;
            BedArt.stage = this.digStage;
            BedArt.until = now + 4000;
            this.post(BedArt.ROOM + name + " is breaking your bed  ·  " + block + " " + (this.digStage + 1) * 10 + "%", IslandIcons.Icon.HOURGLASS, now, false);
            return;
        }
        // enemies near the bed: the nearest, and how many
        double limit = this.range.get(), best = Double.MAX_VALUE;
        String nearest = null;
        int count = 0;
        List<EntityPlayer> players = world.field_73010_i;
        for (int i = 0; i < players.size(); i++) {
            EntityPlayer p = players.get(i);
            if (!GaleBeds.enemy(p, this.teammates.get())) continue;
            double dx = p.field_70165_t - bed[0], dy = p.field_70163_u - bed[1], dz = p.field_70161_v - bed[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > limit) continue;
            count++;
            if (d < best) {
                best = d;
                nearest = p.func_70005_c_();
            }
        }
        if (nearest != null) {
            this.post(nearest + " is near your bed  ·  " + Math.round(best) + "m" + (count > 1 ? "  (+" + (count - 1) + ")" : ""), IslandIcons.Icon.PIN, now, false);
        } else {
            this.shown = "";
        }
    }

    /** Shows the line (or keeps it up: again when it changes, and twice a second while it lasts). */
    private void post(String text, IslandIcons.Icon icon, long now, boolean once) {
        if (text.equals(this.shown) && (once || now - this.shownAt < 500)) return;
        this.shown = text;
        this.shownAt = now;
        WaterMarkModule wm = FuriahsVisuals.instance == null ? null : FuriahsVisuals.instance.getWaterMark();
        if (wm != null) wm.uiNotice("bedtracker", text, icon);
    }
}
