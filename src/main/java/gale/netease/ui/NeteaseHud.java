package gale.netease.ui;

import gale.event.impl.Render2DEvent;
import gale.netease.NeteaseModule;
import gale.netease.NeteasePlayer;
import gale.netease.model.NeteaseTrack;
import gale.spotify.ui.Covers;
import gale.spotify.ui.Icons;
import gale.util.Anim;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The NetEase card on screen, like Spotify's but in NetEase red: cover, song, singer, progress; the bars at the top
 * right bounce on the song's beats. A HUD element (open the chat to move or resize it); fades in and out.
 */
public final class NeteaseHud {
    public static final float W = 170, H = 42;
    public static final int RED = 0xEC4141;
    private static final Anim.Clock CLOCK = new Anim.Clock();
    private static float shown;
    private static NeteaseTrack last;
    private static final float[] bars = new float[3];

    private NeteaseHud() { }

    public static void draw(NeteaseModule m, Render2DEvent e) {
        float dt = CLOCK.tick();
        if (!m.hud.get()) return;
        NeteasePlayer p = m.music();
        NeteaseTrack t = p.current;
        boolean want = t != null && p.active() && !(m.hideWhenPaused.get() && p.state == NeteasePlayer.State.PAUSED);
        if (want) last = t;
        shown = Anim.approach(shown, want ? 1f : 0f, want ? 10f : 13f, dt);
        if (shown <= 0.03f || last == null) return;
        m.box.layout(W, H, e.width(), e.height());
        float s = m.box.scale(), k = 0.94f + 0.06f * Anim.easeOutCubic(shown);
        float x = m.box.x() + W * s * (1f - k) / 2f, y = m.box.y() + H * s * (1f - k) / 2f;
        draw(m, want ? t : last, p, x, y, s * k, (int) (255 * shown), dt);
    }

    /** At x, y (screen coordinates), at this scale. */
    public static void draw(NeteaseModule m, NeteaseTrack t, NeteasePlayer p, float x, float y, float s, int alpha, float dt) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(s, s, 1f);
        RoundRect.draw(0, 0, W, H, 6, ((alpha * 0xD8 / 255) << 24) | 0x16161A);
        Covers.draw(m.covers, t.cover(96), 5, 5, 32, alpha);
        float tx = 42, tw = W - tx - 18;
        String name = t.name;
        int nw = Icons.width(name);
        RenderUtil.scissor(x + tx * s, y, tw * s, H * s);
        if (nw > tw) {
            float gap = 24, offset = (System.currentTimeMillis() / 35L) % (long) (nw + gap);
            Icons.text(name, tx - offset, 6, (alpha << 24) | 0xFFFFFF);
            Icons.text(name, tx - offset + nw + gap, 6, (alpha << 24) | 0xFFFFFF);
        } else {
            Icons.text(name, tx, 6, (alpha << 24) | 0xFFFFFF);
        }
        RenderUtil.endScissor();
        String sub = p.state == NeteasePlayer.State.LOADING ? "加载中…" : p.state == NeteasePlayer.State.ERROR ? p.message : t.artists;
        Icons.text(Icons.fit(sub, tw), tx, 17, (alpha << 24) | (p.state == NeteasePlayer.State.ERROR ? 0xE8A33C : 0xB3B3B3));
        long pos = p.positionMs();
        if (p.state == NeteasePlayer.State.PLAYING) {
            // the bars jump with the beat and settle back
            float beat = p.beatPulse(pos);
            long now = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                float idle = 0.35f + 0.25f * (float) Math.abs(Math.sin(now / (200.0 + i * 45) + i * 1.7));
                float target = Math.min(1f, idle + beat * (0.9f - i * 0.15f));
                bars[i] = Anim.approach(bars[i], target, target > bars[i] ? 30f : 9f, dt);
                float bh = 1.5f + 5.5f * bars[i];
                RoundRect.draw(W - 13 + i * 3, 13 - bh, W - 11.5f + i * 3, 13, 0.4f, (alpha << 24) | RED);
            }
        } else {
            Icons.pause(W - 9, 9.5f, 6, (alpha << 24) | 0xB3B3B3);
        }
        float bx = tx, bw = W - tx - 6, by = H - 9;
        float f = t.durationMs <= 0 ? 0 : Math.min(1f, pos / (float) t.durationMs);
        RoundRect.draw(bx, by, bx + bw, by + 2, 1, ((alpha * 0x60 / 255) << 24) | 0xFFFFFF);
        if (f > 0) RoundRect.draw(bx, by, bx + Math.max(2, bw * f), by + 2, 1, (alpha << 24) | RED);
        GlStateManager.pushMatrix();
        GlStateManager.translate(bx, by - 8, 0);
        GlStateManager.scale(0.6f, 0.6f, 1f);
        String time = Icons.time(pos) + " / " + Icons.time(t.durationMs);
        Icons.text(time, bw / 0.6f - Icons.width(time), 0, (alpha << 24) | 0x8A8A8A);
        GlStateManager.popMatrix();
        GlStateManager.popMatrix();
    }
}
