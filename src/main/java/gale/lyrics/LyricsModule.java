package gale.lyrics;

import gale.event.impl.Render3DEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.render.CjkFont;
import gale.render.DeepGlow;
import gale.render.GlState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lyrics floating in the world, in time with the song playing (NetEase in Gale, or Spotify): each line appears a few
 * blocks in front of you, a little to the side, its characters popping in as they are sung, jittering like hand-drawn
 * animation, with a soft white-hot glow; when the next line comes the last one drifts up and fades. Or, as "Text
 * flash", big words landing in the middle of the screen. After DangoMusic's 3D lyrics.
 */
public final class LyricsModule extends Module {
    public static LyricsModule instance;
    private static final Object NOT_FOUND = new Object();
    private static final float LINE_H = 11, SIZE = 10;

    public final ModeSetting style = mode("Style", "3D", "3D", "Text flash").describe("3D：歌词浮在世界里；Text flash：词一个个砸到屏幕中间（网易云的歌会跟着节拍跳）");
    public final ModeSetting follow = mode("Follow", "Auto", "Auto", "NetEase", "Spotify").describe("跟哪个播放器的歌：Auto 是正在放的那个（两个都在放时先网易云）");
    public final ModeSetting spotifySource = mode("Spotify lyrics", "Auto", "Auto", "NetEase", "LRCLIB")
        .describe("Spotify 的歌去哪找歌词：Auto 是中日韩的歌先找网易云，其它先找 LRCLIB");
    public final NumberSetting offset = number("Offset", 0, -2000, 2000, 50).suffix("ms").describe("歌词早了就往负调，晚了往正调（Spotify 有网络延迟，常要 +200 左右）");
    public final NumberSetting scale = number("Scale", 1, 0.5, 3, 0.05).describe("歌词大小");
    public final NumberSetting distance = number("Distance", 10, 3, 40, 0.5).suffix(" 格").describe("歌词出现在多远（格）");
    public final BoolSetting glow = bool("Glow", true).describe("Deep Glow 泛光：中间白热，外圈一大片柔光");
    public final ModeSetting glowColor = mode("Glow color", "Text", "Text", "Pink", "Blue", "Gold").describe("泛光的颜色：跟文字一样、粉、蓝、金");
    public final NumberSetting glowStrength = number("Glow strength", 0.3, 0, 3, 0.05).describe("泛光亮度");
    public final NumberSetting glowRadius = number("Glow radius", 0.5, 0, 1, 0.05).describe("泛光范围：越大越宽越柔");
    public final BoolSetting smoothFont = bool("Smooth font", true).describe("清晰字体（电脑里的微软雅黑等，中文也清楚）；关掉用 MC 的像素字");
    public final ColorSetting color = color("Color", 0xFFF7E1D7).describe("文字颜色");
    public final BoolSetting wiggle = bool("Wiggle", true).describe("每个字低帧率地轻轻抖，像手绘动画");
    public final NumberSetting wiggleFps = number("Wiggle fps", 8, 4, 24, 1).describe("抖动的帧率：越低越像手绘");
    public final NumberSetting wiggleAmount = number("Wiggle amount", 0.8, 0, 2, 0.05).describe("抖动幅度");
    public final BoolSetting wordPop = bool("Word pop", true).describe("唱到哪个字，哪个字弹出来（网易云有逐字时间的歌最准）");
    public final NumberSetting flashSize = number("Flash size", 1, 0.5, 2, 0.05).describe("Text flash 的字大小");
    public final BoolSetting facePlayer = bool("Face player", true).describe("歌词一直转过来面对你");
    public final BoolSetting keepAway = bool("Keep away", true).describe("走近时歌词往后退，不会撞进脸里");
    public final BoolSetting stayOnScreen = bool("Stay on screen", false).describe("转头时歌词会滑回视野里，不会跑出屏幕");
    public final NumberSetting spread = number("Spread", 30, 0, 60, 1).suffix("°").describe("每句歌词左右最多偏多少度");
    public final BoolSetting splitLines = bool("Split lines", true).describe("网易云常把两句写成一行：拆开分别出现");

    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gale lyrics");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();
    private final TextFlash flash = new TextFlash();
    public final DeepGlow deepGlow = new DeepGlow();
    private final List<Floating> fading = new ArrayList<>();
    private NowPlaying current;
    private String currentKey;
    private double songPos;
    private long lastSeenNanos, keepNanos;
    private int lastIndex = -1;
    private Floating active;
    private boolean wasPaused, glowPass;

    public LyricsModule() {
        super("Lyrics", Category.RENDER, "3D 歌词：网易云或 Spotify 正在放的歌，歌词一句句浮在面前，逐字弹出，带泛光（仿 DangoMusic）");
        instance = this;
        for (gale.module.setting.Setting<?> s : new gale.module.setting.Setting<?>[]{ scale, distance, glow, smoothFont, color, wiggle, wordPop, facePlayer,
            keepAway, stayOnScreen, spread }) s.visibleWhen(() -> style.is("3D"));
        glowColor.visibleWhen(() -> style.is("3D") && glow.get());
        glowStrength.visibleWhen(() -> style.is("3D") && glow.get());
        glowRadius.visibleWhen(() -> style.is("3D") && glow.get());
        wiggleFps.visibleWhen(() -> style.is("3D") && wiggle.get());
        wiggleAmount.visibleWhen(() -> style.is("3D") && wiggle.get());
        flashSize.visibleWhen(() -> style.is("Text flash"));
        spotifySource.onChange(v -> cache.keySet().removeIf(k -> k.startsWith("spotify:")));
        on(Render3DEvent.class, this::render);
    }

    @Override
    protected void onDisable() {
        current = null;
        currentKey = null;
        active = null;
        fading.clear();
        flash.reset();
        lastIndex = -1;
    }

    @Override
    public String getInfo() {
        if (currentKey == null) return "没有在放的歌";
        Object l = cache.get(currentKey);
        if (l == null) return "找歌词中…";
        return l instanceof Lyrics ? ((Lyrics) l).source : "没有歌词";
    }

    /** The lyrics found for the song playing (null while looking, or if there are none). */
    public Lyrics lyrics() {
        Object l = currentKey == null ? null : cache.get(currentKey);
        return l instanceof Lyrics ? (Lyrics) l : null;
    }

    /** True once the search for the song playing is over (found or not). */
    public boolean looked() { return currentKey != null && cache.containsKey(currentKey); }

    /** The line showing now (for the tests), or null. */
    public String showing() {
        Floating f = active;
        return f == null ? null : String.join(" ", f.lines);
    }

    /** Where the line showing now floats (world coordinates; for the tests), or null. */
    public double[] showingAt() {
        Floating f = active;
        return f == null ? null : new double[]{ f.x, f.y, f.z };
    }

    /** For the tests: put lyrics in for a song key, as if they had been found. */
    public void provide(String key, Lyrics l) { cache.put(key, l == null ? NOT_FOUND : l); }

    private void request(NowPlaying s) {
        String key = s.key;
        if (cache.containsKey(key) || !pending.add(key)) return;
        String prefer = spotifySource.get();
        fetcher.execute(() -> {
            try {
                Lyrics l = LyricsProvider.find(s, prefer);
                cache.put(key, l == null ? NOT_FOUND : l);
            } catch (Exception e) {
                cache.put(key, NOT_FOUND);
            } finally {
                pending.remove(key);
            }
        });
    }

    private void updateClock() {
        long now = System.nanoTime();
        NowPlaying s = NowPlaying.current(follow.get());
        if (s == null) {
            // a moment's grace (a refresh that came back empty, a song loading)
            if (current != null && (now - lastSeenNanos) / 1e9 > 3) {
                current = null;
                currentKey = null;
                if (active != null) startFade(active);
                active = null;
                lastIndex = -1;
            }
            return;
        }
        lastSeenNanos = now;
        double p = s.position() + offset.get();
        if (!s.key.equals(currentKey)) {
            currentKey = s.key;
            songPos = p;
            if (active != null) startFade(active);
            active = null;
            lastIndex = -1;
            request(s);
        } else {
            // a jump (seek, or a refresh that corrected the guess): start over at the line playing
            if (Math.abs(p - songPos) > 300) lastIndex = -1;
            songPos = p;
        }
        current = s;
    }

    private void render(Render3DEvent e) {
        updateClock();
        if (Lyrics.splitLines != splitLines.get()) {
            Lyrics.splitLines = splitLines.get();
            lastIndex = -1;
        }
        NowPlaying s = current;
        Lyrics lyrics = lyrics();
        boolean paused = s != null && !s.playing;
        if (s != null && lyrics != null) {
            int idx = lyrics.indexAt((long) songPos);
            if (paused) {
                if (active != null) startFade(active);
                active = null;
                lastIndex = idx;
                wasPaused = true;
            } else if (idx != lastIndex || wasPaused) {
                boolean resumed = wasPaused && idx == lastIndex;
                wasPaused = false;
                lastIndex = idx;
                if (active != null) startFade(active);
                active = null;
                List<LyricLine> lines = lyrics.lines();
                if (idx >= 0 && !lines.get(idx).text.trim().isEmpty()) {
                    LyricLine line = lines.get(idx);
                    long next = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs : line.timeMs + 5000;
                    Floating f = spawn(line, next);
                    if (resumed) f.resumedAt = System.nanoTime();
                    active = f;
                }
            }
        }
        if (style.is("3D")) keepOnScreen();
        long songNow = (long) songPos;
        float beat = s == null ? 0 : s.beat();
        if (style.is("Text flash")) {
            flash.draw(s != null && !paused ? lyrics : null, songNow, beat, flashSize.getFloat());
            return;
        }
        if (active == null && fading.isEmpty()) return;
        GlState saved = GlState.save();
        try {
            if (glow.get() && glowStrength.get() > 0.01 && deepGlow.available()) {
                try {
                    if (deepGlow.begin()) {
                        glowPass = true;
                        try {
                            drawWorld(songNow, false);
                        } finally {
                            glowPass = false;
                        }
                        float[] tint = glowTint();
                        deepGlow.end(tint[0], tint[1], tint[2], glowStrength.getFloat() * 4, glowRadius.getFloat());
                    }
                } catch (Throwable t) {
                    glowPass = false;
                    deepGlow.disable(t);
                }
            }
            drawWorld(songNow, true);
        } finally {
            saved.restore();
        }
    }

    private void startFade(Floating f) {
        f.fadeStartNanos = System.nanoTime();
        fading.add(f);
    }

    // ------------------------------------------------------------------ a line appearing

    /** Words to time when the song gives no word times: by spaces, CJK two characters at a time. */
    private static List<String> splitWords(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                flush(sb, out);
                cjk = 0;
                continue;
            }
            if (c >= '⺀') {
                if (sb.length() > 0 && cjk == 0) flush(sb, out);
                sb.append(c);
                if (++cjk >= 2) {
                    flush(sb, out);
                    cjk = 0;
                }
                continue;
            }
            if (cjk > 0) {
                flush(sb, out);
                cjk = 0;
            }
            sb.append(c);
        }
        flush(sb, out);
        return out;
    }

    private static void flush(StringBuilder sb, List<String> out) {
        if (sb.length() > 0) out.add(sb.toString());
        sb.setLength(0);
    }

    /** When each (non-space) character pops in, in ms after the line starts. */
    private static long[] charTimes(LyricLine line, long nextMs) {
        List<Long> times = new ArrayList<>();
        if (line.words != null) {
            for (LyricLine.Word w : line.words) {
                long at = Math.max(w.startMs - line.timeMs, 0);
                for (int i = 0; i < w.text.length(); i++) if (!Character.isWhitespace(w.text.charAt(i))) times.add(at);
            }
        } else {
            List<String> toks = splitWords(line.text);
            long dur = Math.max(nextMs - line.timeMs, 300);
            double span = Math.min(dur * 0.75, toks.size() * 330.0);
            for (int i = 0; i < toks.size(); i++) {
                long at = (long) (span * i / Math.max(1, toks.size()));
                for (int k = 0; k < toks.get(i).length(); k++) times.add(at);
            }
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    /** A line, a few blocks in front of the eyes, a little to the side and up. */
    private Floating spawn(LyricLine line, long nextMs) {
        List<String> lines = wrap(line.text, 200);
        int chars = 0;
        for (String l : lines) chars += l.length();
        long lineDur = Math.max(nextMs - line.timeMs, 400);
        long reveal = Math.min(Math.max(chars * 55L, 250), (long) (lineDur * 0.6));
        long[] ct = charTimes(line, nextMs);
        Entity p = mc.getRenderViewEntity();
        if (p == null) return new Floating(lines, line.timeMs, reveal, 0, 0, 0, 0, 0, ct);
        float yaw = p.rotationYaw + (random.nextFloat() * 2 - 1) * spread.getFloat();
        float pitch = Math.max(-60, Math.min(30, p.rotationPitch - 3 - random.nextFloat() * 10));
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), d = distance.get();
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        return new Floating(lines, line.timeMs, reveal, p.posX + dx * d, p.posY + p.getEyeHeight() + dy * d, p.posZ + dz * d, yaw, pitch, ct);
    }

    /** Breaks at spaces (or anywhere, for CJK) to fit maxWidth font units. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == ' ') lastSpace = sb.length() - 1;
            if (strW(sb.toString()) <= maxWidth || sb.length() <= 1) continue;
            if (lastSpace > 0) {
                out.add(sb.substring(0, lastSpace).trim());
                String rest = sb.substring(lastSpace + 1);
                sb.setLength(0);
                sb.append(rest);
            } else {
                out.add(sb.substring(0, sb.length() - 1).trim());
                sb.setLength(0);
                sb.append(c);
            }
            lastSpace = -1;
        }
        if (sb.toString().trim().length() > 0) out.add(sb.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    private float strW(String s) { return smoothFont.get() ? CjkFont.width(s, SIZE, CjkFont.PLAIN) : mc.fontRendererObj.getStringWidth(s); }

    private float charW(char c) { return smoothFont.get() ? CjkFont.width(String.valueOf(c), SIZE, CjkFont.PLAIN) : mc.fontRendererObj.getCharWidth(c); }

    private float[] glowTint() {
        switch (glowColor.get()) {
            case "Pink": return new float[]{ 1f, 0.38f, 0.62f };
            case "Blue": return new float[]{ 0.32f, 0.66f, 1f };
            case "Gold": return new float[]{ 1f, 0.74f, 0.3f };
            default: return new float[]{ 1f, 1f, 1f };
        }
    }

    // ------------------------------------------------------------------ drawing

    private static float hashRand(int k, long frame, int salt, int seed) {
        int h = k * 73856093 ^ (int) (frame * 19349663L) ^ salt * 83492791 ^ seed;
        h ^= h >>> 13;
        h *= 1540483477;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    private void drawGlyph(char c, float x, float y, int rgb, float a, boolean smooth, boolean shadow) {
        if (smooth) {
            float baseline = y + 7.6f;
            if (shadow) {
                GlStateManager.color(0, 0, 0, 0.45f * a);
                CjkFont.draw(String.valueOf(c), x + 0.6f, baseline + 0.6f, SIZE, CjkFont.PLAIN);
            }
            GlStateManager.color((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, a);
            CjkFont.draw(String.valueOf(c), x, baseline, SIZE, CjkFont.PLAIN);
        } else {
            int alpha = (int) (a * 255);
            if (alpha < 5) return;
            mc.fontRendererObj.drawString(String.valueOf(c), x, y, alpha << 24 | rgb, shadow);
        }
    }

    /** One line block, centred at the origin of the current matrix, its characters animated. */
    private void drawAnimated(List<String> lines, long elapsedMs, long revealMs, float alphaMul, long[] charTimes) {
        boolean smooth = smoothFont.get(), pop = charTimes != null && charTimes.length > 0;
        int total = 0;
        for (String l : lines) total += l.length();
        total = Math.max(total, 1);
        float y = -(lines.size() * LINE_H) / 2;
        int k = 0, n = 0;
        List<float[]> glyphs = new ArrayList<>();
        List<Character> chars = new ArrayList<>();
        for (String line : lines) {
            float x = -strW(line) / 2;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (pop && !Character.isWhitespace(c)) {
                    long at = charTimes[Math.min(n, charTimes.length - 1)];
                    n++;
                    float t = (elapsedMs - at) / 160f;
                    if (t >= 0) {
                        float a = Math.max(0, Math.min(1, t * 3)) * alphaMul, back = Math.max(0, Math.min(1, 1 - t));
                        if (a > 0.02f) {
                            glyphs.add(new float[]{ x, y, a, k, 1 + 0.55f * back * back });
                            chars.add(c);
                        }
                    }
                } else if (!pop) {
                    float appearAt = (float) k / total * revealMs;
                    float a = Math.max(0, Math.min(1, (elapsedMs - appearAt) / 180f));
                    a = a * a * (3 - 2 * a);
                    if (a * alphaMul > 0.02f && !Character.isWhitespace(c)) {
                        glyphs.add(new float[]{ x, y + (1 - a) * 5, a * alphaMul, k, 1 });
                        chars.add(c);
                    }
                }
                x += charW(c);
                k++;
            }
            y += LINE_H;
        }
        if (glyphs.isEmpty()) return;
        if (smooth) CjkFont.begin();
        int seed = lines.hashCode(), rgb = color.get() & 0xFFFFFF;
        long frame = System.currentTimeMillis() * Math.max(1, wiggleFps.getInt()) / 1000L;
        float amt = wiggleAmount.getFloat();
        boolean wig = wiggle.get() && amt > 0.001f;
        for (int i = 0; i < glyphs.size(); i++) {
            float[] g = glyphs.get(i);
            char c = chars.get(i);
            int gk = (int) g[3];
            float popScale = g[4];
            if (!wig && popScale == 1f) {
                drawGlyph(c, g[0], g[1], rgb, g[2], smooth, !glowPass);
                continue;
            }
            float rot = wig ? hashRand(gk, frame, 1, seed) * 9 * amt : 0;
            float dx = wig ? hashRand(gk, frame, 2, seed) * 0.8f * amt : 0;
            float dy = wig ? hashRand(gk, frame, 3, seed) * 1.6f * amt : 0;
            float sc = (wig ? 1 + hashRand(gk, frame, 4, seed) * 0.12f * amt : 1) * popScale;
            float cx = g[0] + charW(c) / 2, cy = g[1] + 3.5f;
            GL11.glPushMatrix();
            GL11.glTranslatef(cx + dx, cy + dy, 0);
            GL11.glRotatef(rot, 0, 0, 1);
            GL11.glScalef(sc, sc, 1);
            GL11.glTranslatef(-cx, -cy, 0);
            drawGlyph(c, g[0], g[1], rgb, g[2], smooth, !glowPass);
            GL11.glPopMatrix();
        }
    }

    private void drawWorld(long songNow, boolean withFading) {
        GlState.prepareText();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        RenderManager rm = mc.getRenderManager();
        long now = System.nanoTime();
        Iterator<Floating> it = fading.iterator();
        while (it.hasNext()) {
            Floating f = it.next();
            float t = (now - f.fadeStartNanos) / 1e9f, a = 1 - t / 0.4f;
            if (a <= 0) {
                if (withFading) it.remove();
                continue;
            }
            draw(rm, songNow, f, a, t * 0.4f);
        }
        Floating f = active;
        if (f != null) draw(rm, songNow, f, appearOf(f), 0);
    }

    private static float appearOf(Floating f) {
        return f.resumedAt == 0 ? 1 : Math.max(0, Math.min(1, (System.nanoTime() - f.resumedAt) / 3e8f));
    }

    private void draw(RenderManager rm, long songNow, Floating f, float alphaMul, float lift) {
        double rx = f.x - rm.viewerPosX, ry = f.y - rm.viewerPosY, rz = f.z - rm.viewerPosZ;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz), minD = distance.get() * 0.6;
        if (keepAway.get() && len >= 1e-4 && len <= minD) {
            double k = minD / len;
            rx *= k;
            ry *= k;
            rz *= k;
            f.x = rm.viewerPosX + rx;
            f.y = rm.viewerPosY + ry;
            f.z = rm.viewerPosZ + rz;
        }
        float yaw, pitch;
        if (facePlayer.get() && len > 1e-4) {
            yaw = (float) Math.toDegrees(Math.atan2(-rx, rz));
            pitch = (float) Math.toDegrees(Math.atan2(-ry, Math.sqrt(rx * rx + rz * rz)));
        } else {
            yaw = f.yaw;
            pitch = f.pitch;
        }
        float s = 0.03f * scale.getFloat() * (distance.getFloat() / 6.5f);
        GL11.glPushMatrix();
        GL11.glTranslated(rx, ry + lift, rz);
        GL11.glRotatef(-yaw, 0, 1, 0);
        GL11.glRotatef(pitch, 1, 0, 0);
        GL11.glScalef(-s, -s, s);
        drawAnimated(f.lines, songNow - f.startMs, f.revealMs, alphaMul, wordPop.get() ? f.charTimes : null);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------ staying in view

    private static double wrapDegrees(double d) {
        double v = d % 360;
        if (v >= 180) v -= 360;
        if (v < -180) v += 360;
        return v;
    }

    /** Lines outside the view glide back to its edge (0.12 s), so turning away never loses them. */
    private void keepOnScreen() {
        long now = System.nanoTime();
        double dt = keepNanos == 0 ? 0.016 : Math.max(0, Math.min(0.1, (now - keepNanos) / 1e9));
        keepNanos = now;
        Entity p = mc.getRenderViewEntity();
        if (!stayOnScreen.get() || p == null) return;
        double vHalf = mc.gameSettings.fovSetting / 2.0;
        double aspect = mc.displayWidth / (double) Math.max(1, mc.displayHeight);
        double hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * aspect));
        double s = 0.03 * scale.get() * (distance.get() / 6.5), k = 1 - Math.exp(-dt / 0.12);
        if (active != null) follow(p, s, hHalf, vHalf, k, active);
        for (Floating f : fading) follow(p, s, hHalf, vHalf, k, f);
    }

    private void follow(Entity p, double s, double hHalf, double vHalf, double k, Floating f) {
        RenderManager rm = mc.getRenderManager();
        double rx = f.x - rm.viewerPosX, ry = f.y - rm.viewerPosY, rz = f.z - rm.viewerPosZ;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-4 || f.lines.isEmpty()) return;
        float widest = 0;
        for (String l : f.lines) widest = Math.max(widest, strW(l));
        double halfW = Math.toDegrees(Math.atan2(widest * s / 2, len)), halfH = Math.toDegrees(Math.atan2(f.lines.size() * LINE_H * s / 2, len));
        double hLim = Math.max(0, hHalf - halfW - 2), vLim = Math.max(0, vHalf - halfH - 2);
        double curYaw = Math.toDegrees(Math.atan2(-rx, rz)), curPitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -ry / len))));
        double dYaw = wrapDegrees(curYaw - p.rotationYaw), dPitch = curPitch - p.rotationPitch;
        double tYaw = Math.max(-hLim, Math.min(hLim, dYaw)), tPitch = Math.max(-vLim, Math.min(vLim, dPitch));
        if (tYaw == dYaw && tPitch == dPitch) return;
        double newYaw = p.rotationYaw + dYaw + (tYaw - dYaw) * k;
        double newPitch = Math.max(-89, Math.min(89, p.rotationPitch + dPitch + (tPitch - dPitch) * k));
        double yr = Math.toRadians(newYaw), pr = Math.toRadians(newPitch);
        f.x = rm.viewerPosX - Math.sin(yr) * Math.cos(pr) * len;
        f.y = rm.viewerPosY - Math.sin(pr) * len;
        f.z = rm.viewerPosZ + Math.cos(yr) * Math.cos(pr) * len;
        f.yaw = (float) (f.yaw + wrapDegrees(newYaw - curYaw));
        f.pitch = (float) Math.max(-90, Math.min(90, f.pitch + (newPitch - curPitch)));
    }

    private static final class Floating {
        final List<String> lines;
        final long startMs, revealMs;
        final long[] charTimes;
        double x, y, z;
        float yaw, pitch;
        long fadeStartNanos, resumedAt;

        Floating(List<String> lines, long startMs, long revealMs, double x, double y, double z, float yaw, float pitch, long[] charTimes) {
            this.lines = lines;
            this.startMs = startMs;
            this.revealMs = revealMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.charTimes = charTimes;
        }
    }
}
