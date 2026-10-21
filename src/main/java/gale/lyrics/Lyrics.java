package gale.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A song's lyrics, in time order, without "pure music, please enjoy" placeholders. {@link #lines} are split where
 * one line holds two sung lines (see {@link LyricSplit}) while splitting is on.
 */
public final class Lyrics {
    /** where they came from: "网易云", "LRCLIB" */
    public final String source;
    private final List<LyricLine> raw;
    private List<LyricLine> split;
    public static volatile boolean splitLines = true;

    public Lyrics(List<LyricLine> lines, String source) {
        this.source = source;
        List<LyricLine> kept = new ArrayList<>();
        for (LyricLine l : lines) if (!isPlaceholder(l.text)) kept.add(l);
        this.raw = Collections.unmodifiableList(kept);
    }

    public List<LyricLine> lines() {
        if (!splitLines) return raw;
        if (split == null) split = Collections.unmodifiableList(LyricSplit.split(raw));
        return split;
    }

    /** The line playing at pos (the last that started), or -1 before the first. */
    public int indexAt(long pos) {
        List<LyricLine> l = lines();
        int lo = 0, hi = l.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).timeMs <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    public boolean isEmpty() { return raw.isEmpty(); }

    /** "纯音乐，请欣赏" and the like: a song without words. */
    static boolean isPlaceholder(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) sb.append(text.charAt(i));
        String t = sb.toString().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        return t.equals("纯音乐") || t.startsWith("纯音乐请欣赏") || t.contains("没有填词的纯音乐") || t.equals("instrumental") || t.equals("纯音乐请您欣赏");
    }
}
