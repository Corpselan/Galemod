package gale.lyrics;

import gale.render.CjkFont;
import gale.render.GlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The other way to show lyrics: big heavy words in the middle of the screen, landing one by one as they are sung, a
 * few rows at a time, now and then one word slammed in alone, tilted; the whole block pulses and shakes on the beat
 * (NetEase songs). Each line picks red or white and a soft halo at random. As in DangoMusic's TextFlash.
 */
final class TextFlash {
    private static final String PUNCT = "，。！？、,.!?;；:：\"“”";
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();
    private Block current;
    private final List<Block> fading = new ArrayList<>();
    private long lastNanos;

    void reset() {
        current = null;
        fading.clear();
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.HANGUL_SYLLABLES
            || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /** Words; CJK in pairs of characters. */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder(), cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushWord(word, out);
                cjk.append(c);
            } else if (Character.isWhitespace(c) || PUNCT.indexOf(c) >= 0) {
                flushWord(word, out);
                flushCjk(cjk, out);
            } else {
                flushCjk(cjk, out);
                word.append(c);
            }
        }
        flushWord(word, out);
        flushCjk(cjk, out);
        return out;
    }

    private static void flushWord(StringBuilder sb, List<String> out) {
        if (sb.toString().trim().length() > 0) out.add(sb.toString().trim());
        sb.setLength(0);
    }

    private static void flushCjk(StringBuilder sb, List<String> out) {
        for (int i = 0; i < sb.length(); i += 2) out.add(sb.substring(i, Math.min(sb.length(), i + 2)));
        sb.setLength(0);
    }

    /** Upper case, without the punctuation around it. */
    private static String shape(String s) {
        String t = s.toUpperCase(Locale.ROOT);
        int a = 0, b = t.length() - 1;
        while (a <= b && (Character.isWhitespace(t.charAt(a)) || "，。,.!?！？".indexOf(t.charAt(a)) >= 0)) a++;
        while (b >= a && (Character.isWhitespace(t.charAt(b)) || "，。,.!?！？".indexOf(t.charAt(b)) >= 0)) b--;
        return t.substring(a, b + 1);
    }

    /** Length counting a CJK character as two. */
    private static int len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) n += isCjk(s.charAt(i)) ? 2 : 1;
        return n;
    }

    private Block build(LyricLine line, long endMs) {
        List<Piece> units = new ArrayList<>();
        if (line.words != null) {
            String pending = "";
            long pendingStart = 0;
            for (LyricLine.Word w : line.words) {
                String t = shape(w.text);
                if (t.isEmpty()) continue;
                if (t.length() == 1 && isCjk(t.charAt(0))) {
                    if (pending.isEmpty()) pendingStart = w.startMs;
                    pending += t;
                    if (pending.length() < 2) continue;
                    units.add(new Piece(pending, pendingStart, false));
                    pending = "";
                    continue;
                }
                if (!pending.isEmpty()) {
                    units.add(new Piece(pending, pendingStart, false));
                    pending = "";
                }
                units.add(new Piece(t, w.startMs, false));
            }
            if (!pending.isEmpty()) units.add(new Piece(pending, pendingStart, false));
        } else {
            List<String> toks = new ArrayList<>();
            for (String t : tokens(line.text)) {
                String s = shape(t);
                if (!s.isEmpty()) toks.add(s);
            }
            long dur = Math.max(endMs - line.timeMs, 300);
            double span = Math.min(dur * 0.8, toks.size() * 380.0);
            for (int i = 0; i < toks.size(); i++) units.add(new Piece(toks.get(i), line.timeMs + (long) (span * i / Math.max(1, toks.size())), false));
        }
        List<Row> rows = new ArrayList<>();
        Row row = new Row();
        int rowLen = 0;
        for (Piece u : units) {
            Piece p = len(u.text) >= 3 && random.nextFloat() < 0.22f ? new Piece(u.text, u.startMs, true) : u;
            int l = len(p.text);
            boolean fits = row.units.isEmpty() || l <= 3 && rowLen + 1 + l <= 8 || rowLen <= 3 && rowLen + 1 + l <= 9;
            if (!fits) {
                rows.add(row);
                row = new Row();
                rowLen = 0;
            }
            row.units.add(p);
            rowLen += rowLen == 0 ? l : l + 1;
        }
        if (!row.units.isEmpty()) rows.add(row);
        boolean red = random.nextFloat() < 0.32f;
        return new Block(line.timeMs, rows, red, red || random.nextFloat() < 0.35f, (random.nextFloat() * 2 - 1) * 12f);
    }

    /** Draws over the world, in window pixels (lyrics null: the last block fades out). */
    void draw(Lyrics lyrics, long pos, float beat, float sizeMul) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 16 : Math.max(0, Math.min(50, (now - lastNanos) / 1e6f));
        lastNanos = now;
        if (lyrics != null) {
            int idx = lyrics.indexAt(pos);
            List<LyricLine> lines = lyrics.lines();
            LyricLine line = idx >= 0 && idx < lines.size() ? lines.get(idx) : null;
            long lineTime = line != null ? line.timeMs : Long.MIN_VALUE;
            if (current == null || current.lineTime != lineTime) {
                if (current != null) {
                    current.fadeStart = now;
                    fading.add(current);
                }
                if (line != null && !line.text.trim().isEmpty()) {
                    long end = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs : line.timeMs + 5000;
                    current = build(line, end);
                } else {
                    current = null;
                }
            }
        } else if (current != null) {
            current.fadeStart = now;
            fading.add(current);
            current = null;
        }
        if (current == null && fading.isEmpty()) return;
        float sw = mc.displayWidth, sh = mc.displayHeight;
        float unitW = Math.min(sw, sh * 16f / 9f) * 0.17f * sizeMul;
        GlState saved = GlState.save();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, sw, sh, 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -2000);
        try {
            GlState.prepareText();
            GlStateManager.disableDepth();
            CjkFont.begin();
            Iterator<Block> it = fading.iterator();
            while (it.hasNext()) {
                Block b = it.next();
                float t = (now - b.fadeStart) / 1e9f / 0.14f;
                if (t >= 1) {
                    it.remove();
                    continue;
                }
                drawBlock(b, Long.MAX_VALUE, sw / 2, sh * 0.46f, sh * 0.42f, unitW, 0, 1 - t, 1 + t * 0.08f, dt);
            }
            if (current != null) drawBlock(current, pos, sw / 2, sh * 0.46f, sh * 0.42f, unitW, beat, 1, 1, dt);
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            saved.restore();
        }
    }

    private void drawBlock(Block b, long pos, float cx, float cy, float maxSlam, float w, float beat, float alpha, float extraScale, float dt) {
        if (pos != Long.MAX_VALUE) {
            Piece slamming = null;
            for (Row r : b.rows) for (Piece u : r.units) if (u.slam && u.startMs <= pos && pos - u.startMs < 240) slamming = u;
            if (slamming != null) {
                drawSlam(b, slamming, pos, cx, cy, maxSlam, w, beat, alpha);
                return;
            }
        }
        List<Row> started = new ArrayList<>();
        for (Row r : b.rows) {
            for (Piece u : r.units) {
                if (u.startMs <= pos) {
                    started.add(r);
                    break;
                }
            }
        }
        List<Row> visible = started.subList(Math.max(0, started.size() - 4), started.size());
        if (visible.isEmpty()) return;
        float[] sizes = new float[visible.size()], heights = new float[visible.size()];
        float gap = w * 0.02f, total = 0;
        for (int i = 0; i < visible.size(); i++) {
            Row r = visible.get(i);
            float w1 = Math.max(CjkFont.width(r.visibleText(pos), 1, CjkFont.DISPLAY), 0.01f);
            float target = Math.max(w * 0.16f, Math.min(w * 0.62f, w / w1));
            r.size = r.size <= 0 ? target : r.size + (target - r.size) * (1 - (float) Math.exp(-dt / 60f));
            sizes[i] = r.size;
            heights[i] = r.size * 0.86f;
            total += heights[i];
        }
        total += gap * (visible.size() - 1);
        float pulse = 1 + 0.09f * beat;
        float shakeX = (random.nextFloat() - 0.5f) * beat * w * 0.05f, shakeY = (random.nextFloat() - 0.5f) * beat * w * 0.05f;
        GL11.glPushMatrix();
        GL11.glTranslatef(cx + shakeX, cy + shakeY, 0);
        GL11.glScalef(pulse * extraScale, pulse * extraScale, 1);
        float y = -total / 2;
        for (int i = 0; i < visible.size(); i++) {
            Row r = visible.get(i);
            String text = r.visibleText(pos);
            long age = pos == Long.MAX_VALUE ? 1000 : pos - r.newestStart(pos);
            float pop = popScale(age), tw = CjkFont.width(text, sizes[i], CjkFont.DISPLAY);
            GL11.glPushMatrix();
            GL11.glTranslatef(0, y + heights[i] / 2, 0);
            GL11.glScalef(pop, pop, 1);
            drawText(text, -tw / 2, sizes[i] * 0.4f, sizes[i], b, alpha);
            GL11.glPopMatrix();
            y += heights[i] + gap;
        }
        GL11.glPopMatrix();
    }

    private void drawSlam(Block b, Piece u, long pos, float cx, float cy, float maxSize, float w, float beat, float alphaMul) {
        float age = pos - u.startMs, t = Math.max(0, Math.min(1, age / 240f));
        float w1 = Math.max(CjkFont.width(u.text, 1, CjkFont.DISPLAY), 0.01f);
        float size = Math.min(w * 3.2f / w1, maxSize);
        float intro = 1 + 0.35f * (float) Math.exp(-age / 45f);
        float outro = t > 0.75f ? 1 - (t - 0.75f) / 0.25f * 0.55f : 1;
        float alpha = (t > 0.85f ? 1 - (t - 0.85f) / 0.15f : 1) * alphaMul;
        float s = intro * outro * (1 + 0.06f * beat), tw = CjkFont.width(u.text, size, CjkFont.DISPLAY);
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy, 0);
        GL11.glRotatef(b.tilt, 0, 0, 1);
        GL11.glScalef(s, s, 1);
        drawText(u.text, -tw / 2, size * 0.4f, size, b, alpha);
        GL11.glPopMatrix();
    }

    private static float popScale(long ageMs) {
        if (ageMs < 0) return 1;
        float t = Math.max(0, Math.min(1, ageMs / 160f)), back = 1 - t;
        return 1 + 0.28f * back * back - 0.04f * (float) Math.sin(t * Math.PI) * (1 - t);
    }

    private static void drawText(String text, float x, float baseline, float size, Block b, float alpha) {
        float r = 1, g = b.red ? 0.17f : 1, bl = b.red ? 0.33f : 1;
        if (b.glow) {
            float[][] rings = b.red ? new float[][]{ { 0.1f, 0.05f }, { 0.05f, 0.1f } } : new float[][]{ { 0.06f, 0.07f } };
            for (float[] ring : rings) {
                GlStateManager.color(r, g, bl, ring[1] * alpha);
                for (int k = 0; k < 8; k++) {
                    double ang = k / 8.0 * Math.PI * 2;
                    CjkFont.draw(text, x + (float) Math.cos(ang) * size * ring[0], baseline + (float) Math.sin(ang) * size * ring[0], size, CjkFont.DISPLAY);
                }
            }
        }
        GlStateManager.color(r, g, bl, alpha);
        CjkFont.draw(text, x, baseline, size, CjkFont.DISPLAY);
    }

    private static final class Block {
        final long lineTime;
        final List<Row> rows;
        final boolean red, glow;
        final float tilt;
        long fadeStart;

        Block(long lineTime, List<Row> rows, boolean red, boolean glow, float tilt) {
            this.lineTime = lineTime;
            this.rows = rows;
            this.red = red;
            this.glow = glow;
            this.tilt = tilt;
        }
    }

    private static final class Piece {
        final String text;
        final long startMs;
        final boolean slam;

        Piece(String text, long startMs, boolean slam) {
            this.text = text;
            this.startMs = startMs;
            this.slam = slam;
        }
    }

    private static final class Row {
        final List<Piece> units = new ArrayList<>();
        float size;

        String visibleText(long pos) {
            StringBuilder sb = new StringBuilder();
            for (Piece u : units) {
                if (u.startMs > pos) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(u.text);
            }
            return sb.toString();
        }

        long newestStart(long pos) {
            long best = Long.MIN_VALUE;
            for (Piece u : units) if (u.startMs <= pos) best = Math.max(best, u.startMs);
            return best;
        }
    }
}
