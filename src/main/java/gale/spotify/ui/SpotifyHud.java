package gale.spotify.ui;

import gale.event.impl.Render2DEvent;
import gale.spotify.SpotifyModule;
import gale.spotify.SpotifyPlayer;
import gale.spotify.model.Playback;
import gale.spotify.model.Track;
import gale.util.Anim;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The small card on screen: cover, song, artist, a progress bar; long names scroll. It is a HUD element (open the
 * chat to move or resize it), and fades in and out with a slight grow when something starts or stops playing.
 */
public final class SpotifyHud {
    public static final float W = 170, H = 42;
    private static final Anim.Clock CLOCK = new Anim.Clock();
    private static float shown;
    private static Playback last;

    private SpotifyHud() { }

    public static void draw(SpotifyModule m, Render2DEvent e) {
        float dt = CLOCK.tick();
        if (!m.hud.get()) return;
        SpotifyPlayer p = m.spotify();
        Playback pb = p.playback;
        boolean want = pb != null && pb.track != null && !(m.hideWhenPaused.get() && !pb.playing);
        if (want) last = pb;
        shown = Anim.approach(shown, want ? 1f : 0f, want ? 10f : 13f, dt);
        // (the font draws text with an alpha under 4 fully opaque: stop before that)
        if (shown <= 0.03f || last == null) return;
        m.box.layout(W, H, e.width(), e.height());
        // grows from 94% to full size around its middle as it fades in
        float s = m.box.scale(), k = 0.94f + 0.06f * Anim.easeOutCubic(shown);
        float x = m.box.x() + W * s * (1f - k) / 2f, y = m.box.y() + H * s * (1f - k) / 2f;
        draw(m, want ? pb : last, x, y, s * k, (int) (255 * shown));
    }

    /** At x, y (screen coordinates), at this scale; also used by the tests and the settings preview. */
    public static void draw(SpotifyModule m, Playback pb, float x, float y, float s, int alpha) {
        Track t = pb.track;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(s, s, 1f);
        RoundRect.draw(0, 0, W, H, 6, ((alpha * 0xD8 / 255) << 24) | 0x121212);
        Covers.draw(m.covers, t.cover, 5, 5, 32, alpha);
        float tx = 42, tw = W - tx - 18;
        // the name scrolls when it does not fit (clipped to the card)
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
        Icons.text(Icons.fit(t.artists, tw), tx, 17, (alpha << 24) | 0xB3B3B3);
        // playing or paused, top right
        if (pb.playing) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                float bh = 2.5f + 3.5f * (float) Math.abs(Math.sin(now / (180.0 + i * 40) + i * 1.7));
                RoundRect.draw(W - 13 + i * 3, 13 - bh, W - 11.5f + i * 3, 13, 0.4f, (alpha << 24) | 0x1DB954);
            }
        } else {
            Icons.pause(W - 9, 9.5f, 6, (alpha << 24) | 0xB3B3B3);
        }
        // progress
        float bx = tx, bw = W - tx - 6, by = H - 9;
        float f = t.durationMs <= 0 ? 0 : Math.min(1f, pb.progressNow() / (float) t.durationMs);
        RoundRect.draw(bx, by, bx + bw, by + 2, 1, ((alpha * 0x60 / 255) << 24) | 0xFFFFFF);
        if (f > 0) RoundRect.draw(bx, by, bx + Math.max(2, bw * f), by + 2, 1, (alpha << 24) | 0x1DB954);
        GlStateManager.pushMatrix();
        GlStateManager.translate(bx, by - 8, 0);
        GlStateManager.scale(0.6f, 0.6f, 1f);
        String time = Icons.time(pb.progressNow()) + " / " + Icons.time(t.durationMs);
        Icons.text(time, bw / 0.6f - Icons.width(time), 0, (alpha << 24) | 0x8A8A8A);
        GlStateManager.popMatrix();
        GlStateManager.popMatrix();
    }
}
