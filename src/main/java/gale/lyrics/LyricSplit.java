package gale.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * NetEase often writes two sung lines as one, joined by a space ("我们的歌 你在听吗"). This splits them where a space
 * stands between two CJK characters (or a full-width space anywhere), so each shows up on its own, at the time its
 * first word is sung (or, without word times, at its share of the line). Pieces under two characters or under half a
 * second apart are put back together. As in DangoMusic.
 */
final class LyricSplit {
    private static final int MIN_CHARS = 2;
    private static final long MIN_MS = 500;

    private LyricSplit() { }

    private static boolean isCjk(char c) { return c >= '⺀' && c != '　'; }

    /** Where each piece starts: 0, then after each splitting space. */
    static List<Integer> cuts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ' ' && c != '　') {
                i++;
                continue;
            }
            int j = i;
            boolean fullWidth = false;
            while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '　')) {
                if (text.charAt(j) == '　') fullWidth = true;
                j++;
            }
            String before = rtrim(text.substring(0, i));
            if (!before.isEmpty() && j < text.length()) {
                char prev = before.charAt(before.length() - 1), next = text.charAt(j);
                if (fullWidth || isCjk(prev) && isCjk(next)) starts.add(j);
            }
            i = j;
        }
        return starts;
    }

    static List<LyricLine> split(List<LyricLine> lines) {
        List<LyricLine> out = new ArrayList<>(lines.size());
        for (int li = 0; li < lines.size(); li++) {
            LyricLine line = lines.get(li);
            Long nextMs = li + 1 < lines.size() ? lines.get(li + 1).timeMs : null;
            List<Seg> segs = segments(line, nextMs);
            if (segs.size() <= 1) {
                out.add(line);
                continue;
            }
            List<Seg> merged = new ArrayList<>();
            for (Seg sg : segs) {
                Seg prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (prev != null && (sg.chars() < MIN_CHARS || prev.chars() < MIN_CHARS || sg.startMs - prev.startMs < MIN_MS)) {
                    prev.join(sg);
                    continue;
                }
                merged.add(sg);
            }
            if (merged.size() > 1 && nextMs != null && nextMs - merged.get(merged.size() - 1).startMs < MIN_MS) {
                Seg last = merged.remove(merged.size() - 1);
                merged.get(merged.size() - 1).join(last);
            }
            if (merged.size() <= 1) {
                out.add(line);
                continue;
            }
            for (Seg sg : merged) out.add(new LyricLine(sg.startMs, sg.text, sg.words));
        }
        out.sort(Comparator.comparingLong(l -> l.timeMs));
        return out;
    }

    private static List<Seg> segments(LyricLine line, Long nextMs) {
        if (line.words != null) {
            StringBuilder full = new StringBuilder();
            for (LyricLine.Word w : line.words) full.append(w.text);
            List<Integer> starts = cuts(full.toString());
            if (starts.size() <= 1) return Collections.emptyList();
            List<Seg> segs = new ArrayList<>();
            for (int ignored : starts) segs.add(new Seg("", Long.MAX_VALUE, new ArrayList<>()));
            int pos = 0;
            for (LyricLine.Word w : line.words) {
                int firstChar = -1;
                for (int i = 0; i < w.text.length(); i++) {
                    if (!Character.isWhitespace(w.text.charAt(i))) {
                        firstChar = i;
                        break;
                    }
                }
                int at = pos + Math.max(firstChar, 0), k = 0;
                for (int s = starts.size() - 1; s >= 0; s--) {
                    if (starts.get(s) <= at) {
                        k = s;
                        break;
                    }
                }
                Seg sg = segs.get(k);
                sg.words.add(w);
                if (firstChar >= 0 && w.startMs < sg.startMs) sg.startMs = w.startMs;
                pos += w.text.length();
            }
            List<Seg> ok = new ArrayList<>();
            for (Seg sg : segs) {
                boolean any = false;
                for (LyricLine.Word w : sg.words) any |= !w.text.trim().isEmpty();
                if (!any) continue;
                StringBuilder t = new StringBuilder();
                for (LyricLine.Word w : sg.words) t.append(w.text);
                sg.text = t.toString().trim();
                ok.add(sg);
            }
            return ok.size() <= 1 ? Collections.<Seg>emptyList() : ok;
        }
        String text = line.text;
        List<Integer> starts = cuts(text);
        if (starts.size() <= 1) return Collections.emptyList();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) parts.add(text.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length()).trim());
        int[] counts = new int[parts.size()];
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            counts[i] = nonSpace(parts.get(i));
            total += counts[i];
        }
        total = Math.max(total, 1);
        long end = nextMs != null ? nextMs : line.timeMs + total * 300L;
        double singing = Math.max(end - line.timeMs, 0) * 0.85;
        List<Seg> segs = new ArrayList<>();
        int before = 0;
        for (int i = 0; i < parts.size(); i++) {
            segs.add(new Seg(parts.get(i), line.timeMs + (long) (singing * before / total), null));
            before += counts[i];
        }
        return segs;
    }

    static int nonSpace(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) n++;
        return n;
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1)) || e > 0 && s.charAt(e - 1) == '　') e--;
        return s.substring(0, e);
    }

    private static final class Seg {
        String text;
        long startMs;
        final List<LyricLine.Word> words;

        Seg(String text, long startMs, List<LyricLine.Word> words) {
            this.text = text;
            this.startMs = startMs;
            this.words = words;
        }

        int chars() { return nonSpace(text); }

        void join(Seg next) {
            text = text + ' ' + next.text;
            if (words != null && next.words != null) words.addAll(next.words);
        }
    }
}
