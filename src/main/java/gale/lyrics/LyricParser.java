package gale.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyric files: LRC ("[01:23.45]text", one time per line, or several for a repeated line) and NetEase's YRC (a time
 * for every word: "[start,length](start,length,0)word(…)word"). Credit lines ("作词：…", "Composer: …") are dropped.
 */
public final class LyricParser {
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern CREDIT = Pattern.compile("^\\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|统筹|词|曲|OP|SP"
        + "|Lyricist|Lyrics by|Composer|Composed by|Producer|Produced by|Arranger|Arranged by)\\s*[:：]", Pattern.CASE_INSENSITIVE);

    private LyricParser() { }

    static boolean credit(String text) { return CREDIT.matcher(text).find(); }

    public static List<LyricLine> yrc(String text) {
        List<LyricLine> out = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            Matcher m = YRC_LINE.matcher(raw.trim());
            if (!m.find()) continue;
            long start = Long.parseLong(m.group(1));
            List<LyricLine.Word> words = new ArrayList<>();
            Matcher w = YRC_WORD.matcher(m.group(3));
            while (w.find()) {
                if (w.group(3).isEmpty()) continue;
                words.add(new LyricLine.Word(Long.parseLong(w.group(1)), Long.parseLong(w.group(2)), w.group(3)));
            }
            if (words.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (LyricLine.Word wd : words) sb.append(wd.text);
            String line = sb.toString().trim();
            if (line.isEmpty() || credit(line)) continue;
            out.add(new LyricLine(start, line, words));
        }
        out.sort(Comparator.comparingLong(l -> l.timeMs));
        return out;
    }

    public static List<LyricLine> lrc(String text) {
        List<LyricLine> out = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String rest = raw.trim();
            List<Long> tags = new ArrayList<>();
            Matcher m = TIME_TAG.matcher(rest);
            while (m.lookingAt()) {
                long min = Long.parseLong(m.group(1)), sec = Long.parseLong(m.group(2));
                String frac = m.group(3) == null ? "" : m.group(3);
                long ms = frac.isEmpty() ? 0 : frac.length() == 1 ? Long.parseLong(frac) * 100 : frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac.substring(0, 3));
                tags.add(min * 60000 + sec * 1000 + ms);
                rest = rest.substring(m.end());
                m = TIME_TAG.matcher(rest);
            }
            String lyric = rest.trim();
            if (tags.isEmpty() || credit(lyric)) continue;
            for (long t : tags) out.add(new LyricLine(t, lyric, null));
        }
        out.sort(Comparator.comparingLong(l -> l.timeMs));
        return out;
    }
}
