package gale.core;

import gale.Gale;
import gale.hud.HudBox;
import gale.hud.HudLayout;
import gale.module.Module;
import gale.util.Anim;
import gale.util.ChatUtil;
import gale.util.ColorUtil;
import gale.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small messages, stacked in a corner (bottom right; open the chat to move them), or in chat while they are switched
 * off. Each slides in, the others glide out of its way, and it slides out when its time is up.
 */
public final class Notifications {
    public enum Kind {
        INFO(0xFF55AAFF), SUCCESS(0xFF55FF77), WARNING(0xFFFFAA33), ERROR(0xFFFF5555);

        final int color;

        Kind(int color) { this.color = color; }
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<Note> NOTES = new CopyOnWriteArrayList<>();
    private static final float H = 18f, GAP = 3f, IN_MS = 380f, OUT_MS = 260f;
    private static final Anim.Clock CLOCK = new Anim.Clock();
    private static HudBox box;
    /** drawn on screen (the HUD module switches this); when off, show() goes to chat */
    public static volatile boolean onScreen = true;
    /** a note when a module is switched on or off */
    public static volatile boolean toggles = true;

    private Notifications() { }

    private static HudBox box() {
        if (box == null) {
            box = HudLayout.box("notifications", "Notifications", null, HudBox.RIGHT, HudBox.BOTTOM, 6, 6, 120, H);
            box.showWhen(() -> onScreen && mc.thePlayer != null);
        }
        return box;
    }

    public static void show(String title, String text, Kind kind, long millis) {
        if (!onScreen) {
            ChatUtil.print(title + ": " + text);
            return;
        }
        NOTES.add(new Note(title, text, kind, millis));
        while (NOTES.size() > 6) NOTES.remove(0);
    }

    public static void info(String text) { show(Gale.NAME, text, Kind.INFO, 2500); }

    public static void success(String text) { show(Gale.NAME, text, Kind.SUCCESS, 2500); }

    public static void warn(String text) { show(Gale.NAME, text, Kind.WARNING, 3500); }

    public static void error(String text) { show(Gale.NAME, text, Kind.ERROR, 4500); }

    /** Called when a module is switched; quiet outside a world. */
    public static void toggled(Module m) {
        if (!toggles || !onScreen || mc.thePlayer == null || m.isAlwaysOn()) return;
        show(m.getName(), m.isEnabled() ? "开启" : "关闭", m.isEnabled() ? Kind.SUCCESS : Kind.ERROR, 1200);
    }

    /** Drawn by the hooks after the HUD. */
    static void render(ScaledResolution sr) {
        HudBox b = box();
        float dt = CLOCK.tick();
        if (NOTES.isEmpty()) return;
        long now = System.currentTimeMillis();
        float maxW = 0, total = 0;
        for (Note n : NOTES) {
            long age = now - n.start;
            if (age > n.millis + OUT_MS) {
                NOTES.remove(n);
                continue;
            }
            n.in = Anim.easeOutExpo(age / IN_MS);
            n.out = age > n.millis ? Anim.easeInCubic((age - n.millis) / OUT_MS) : 0f;
            n.w = RenderUtil.textWidth(n.line()) + 12;
            maxW = Math.max(maxW, n.w);
            // a note on its way out gives its place up as it goes
            total += (H + GAP) * (1f - n.out);
        }
        if (NOTES.isEmpty()) return;
        b.layout(maxW, Math.max(H, total - GAP), sr.getScaledWidth(), sr.getScaledHeight());
        boolean right = b.right(), up = b.bottom();
        float boxH = Math.max(H, total - GAP), slot = 0;
        b.push();
        // the newest nearest the anchored edge
        for (int i = NOTES.size() - 1; i >= 0; i--) {
            Note n = NOTES.get(i);
            float target = up ? boxH - slot - H : slot;
            n.y = n.y < -0.5f ? target : Anim.approach(n.y, target, 15f, dt);
            slot += (H + GAP) * (1f - n.out);
            float shown = n.in * (1f - n.out);
            float off = (1f - n.in) * (n.w + 8) + n.out * (n.w + 8);
            float x = right ? maxW - n.w + off : b.centered() ? (maxW - n.w) / 2f : -off;
            float y = n.y;
            int a = Math.max(4, (int) (255 * shown));
            RenderUtil.rect(x, y, x + n.w, y + H, ColorUtil.argb((int) (170 * shown), 12, 12, 16));
            float done = Math.min(1f, (float) (now - n.start) / n.millis);
            RenderUtil.rect(x, y + H - 1.5f, x + n.w * (1f - done), y + H, ColorUtil.withAlpha(n.kind.color, a));
            RenderUtil.textBlended(n.line(), x + 6, y + 5, ColorUtil.withAlpha(0xFFFFFFFF, a));
        }
        b.pop();
    }

    private static final class Note {
        final String title, text;
        final Kind kind;
        final long start = System.currentTimeMillis(), millis;
        float in, out, w, y = -1f;

        Note(String title, String text, Kind kind, long millis) {
            this.title = title;
            this.text = text;
            this.kind = kind;
            this.millis = millis;
        }

        String line() { return title + "  §7" + text; }
    }
}
