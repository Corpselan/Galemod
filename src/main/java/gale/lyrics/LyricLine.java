package gale.lyrics;

import java.util.List;

/** One line of lyrics: when it starts (ms into the song), its text, and, if known, when each word is sung. */
public final class LyricLine {
    public final long timeMs;
    public final String text;
    /** null when only the line's start is known (LRC) */
    public final List<Word> words;

    public LyricLine(long timeMs, String text, List<Word> words) {
        this.timeMs = timeMs;
        this.text = text;
        this.words = words;
    }

    /** A word (or a CJK character or two) with when it starts and how long it is sung. */
    public static final class Word {
        public final long startMs, durationMs;
        public final String text;

        public Word(long startMs, long durationMs, String text) {
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.text = text;
        }
    }

    @Override
    public String toString() { return "[" + timeMs + "] " + text; }
}
