package gale.netease;

import gale.netease.model.NeteaseTrack;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Plays NetEase songs in the game itself (unlike Spotify, the sound is ours): the song is downloaded whole, decoded
 * frame by frame (JLayer) and written to the sound card. While decoding it listens for beats (sudden jumps in the bass),
 * which the lyrics pulse to. A queue with shuffle and repeat; everything slow runs on background threads, and the
 * game reads the fields. As in DangoMusic's MusicPlayer.
 */
public final class NeteasePlayer {
    public enum State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    public enum Repeat { OFF, LIST, ONE }

    public static final NeteasePlayer INSTANCE = new NeteasePlayer();

    public volatile State state = State.STOPPED;
    public volatile NeteaseTrack current;
    /** what went wrong, or a note like "only a sample" (Chinese) */
    public volatile String message = "";
    public volatile List<NeteaseTrack> queue = Collections.emptyList();
    public volatile int index = -1;
    public volatile boolean shuffle;
    public volatile Repeat repeat = Repeat.LIST;
    /** 0..1 */
    public volatile float volume = 0.5f;
    /** kbit/s asked for: 128000, 192000 or 320000 (VIP for more than 128k on most songs) */
    public volatile int bitrate = 192000;
    /** no sound card: the song is timed but not heard (the tests) */
    public static volatile boolean silent;
    /** told (on a background thread) when another song starts loading */
    public volatile Consumer<NeteaseTrack> onTrack;
    /** where to note what happened to each song (the game's log) */
    public static volatile Consumer<String> log = s -> { };

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gale NetEase loader");
        t.setDaemon(true);
        return t;
    });
    private final Random random = new Random();
    private final ArrayDeque<long[]> beats = new ArrayDeque<>();
    private volatile int generation;
    private volatile byte[] audio;
    private volatile Sink sink;
    private volatile long seekBaseMs, pausedAtMs;
    private volatile int failsInRow;

    private NeteasePlayer() { }

    public long durationMs() {
        NeteaseTrack t = current;
        return t == null ? 0 : t.durationMs;
    }

    /** Where the song is now (what is heard, not what is decoded). */
    public long positionMs() {
        switch (state) {
            case PLAYING: {
                // (between a seek and the sound starting again: where it is going)
                Sink s = sink;
                return seekBaseMs + (s == null ? 0 : s.positionUs() / 1000);
            }
            case PAUSED: return pausedAtMs;
            default: return 0;
        }
    }

    public boolean active() {
        State s = state;
        return current != null && (s == State.PLAYING || s == State.PAUSED || s == State.LOADING);
    }

    /** How strongly a beat is landing at pos: 0..1, rising on each beat and falling off over a few hundred ms. */
    public float beatPulse(long pos) {
        float best = 0;
        synchronized (beats) {
            for (long[] b : beats) {
                long dt = pos - b[0];
                if (dt < 0 || dt > 600) continue;
                best = Math.max(best, Float.intBitsToFloat((int) b[1]) * (float) Math.exp(-dt / 110f));
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ controls

    public void playQueue(List<NeteaseTrack> tracks, int start) {
        queue = new ArrayList<>(tracks);
        failsInRow = 0;
        playIndex(start);
    }

    public void playIndex(int i) {
        List<NeteaseTrack> q = queue;
        if (q.isEmpty()) return;
        int idx = (i % q.size() + q.size()) % q.size();
        index = idx;
        NeteaseTrack track = q.get(idx);
        int gen = ++generation;
        stopSink();
        audio = null;
        current = track;
        state = State.LOADING;
        message = "加载中…";
        Consumer<NeteaseTrack> c = onTrack;
        loader.execute(() -> {
            if (c != null && gen == generation) c.accept(track);
            load(gen, track);
        });
    }

    /** Puts a song right after the one playing (or plays it, if nothing is). */
    public void playNext(NeteaseTrack t) {
        List<NeteaseTrack> q = new ArrayList<>(queue);
        if (current == null || q.isEmpty()) {
            playQueue(Collections.singletonList(t), 0);
            return;
        }
        q.add(Math.min(q.size(), index + 1), t);
        queue = q;
    }

    public void next() {
        List<NeteaseTrack> q = queue;
        if (q.isEmpty()) return;
        playIndex(shuffle && q.size() > 1 ? pickRandom(q.size()) : index + 1);
    }

    public void previous() {
        if (positionMs() > 3000) {
            seek(0);
            return;
        }
        playIndex(index - 1);
    }

    private int pickRandom(int size) {
        int r;
        do r = random.nextInt(size); while (r == index);
        return r;
    }

    public void togglePause() {
        switch (state) {
            case PLAYING: {
                pausedAtMs = positionMs();
                state = State.PAUSED;
                Sink s = sink;
                if (s != null) s.pause();
                break;
            }
            case PAUSED: {
                Sink s = sink;
                if (s != null) {
                    state = State.PLAYING;
                    s.resume();
                } else {
                    seek(pausedAtMs);
                }
                break;
            }
            case STOPPED:
            case ERROR:
                if (current != null) playIndex(index);
                break;
            default: break;
        }
    }

    public void seek(long ms) {
        byte[] bytes = audio;
        if (bytes == null) return;
        long target = Math.max(0, Math.min(ms, Math.max(0, durationMs() - 500)));
        if (state == State.PAUSED) pausedAtMs = target;
        int gen = ++generation;
        stopSink();
        seekBaseMs = target;
        startDecode(gen, bytes, target);
    }

    public void stop() {
        generation++;
        stopSink();
        audio = null;
        current = null;
        state = State.STOPPED;
        message = "";
    }

    public void cycleRepeat() { repeat = repeat == Repeat.LIST ? Repeat.ONE : repeat == Repeat.ONE ? Repeat.OFF : Repeat.LIST; }

    // ------------------------------------------------------------------ loading and decoding

    private String noRights() { return NeteaseApi.loggedIn() ? "这首歌没有版权或要 VIP" : "这首歌要登录（或 VIP）才能放"; }

    private void load(int gen, NeteaseTrack track) {
        try {
            if (!track.canPlay()) {
                fail(gen, noRights());
                return;
            }
            NeteaseApi.SongUrl url = NeteaseApi.songUrl(track.id, bitrate);
            if (gen != generation) return;
            byte[] bytes = NeteaseApi.request(url.url, "*/*", 64 << 20);
            if (gen != generation) return;
            // a song NetEase will not give out comes back as a page, not sound
            if (bytes == null || bytes.length < 10000 || !looksLikeMp3(bytes)) {
                log.accept(track + ": not playable (" + (bytes == null ? "no answer" : bytes.length + " bytes, not MP3") + ")");
                fail(gen, noRights());
                return;
            }
            log.accept(track + ": " + bytes.length / 1024 + " KB" + (url.trialOnly ? ", sample only" : ""));
            audio = bytes;
            message = url.trialOnly ? "只能试听片段" : "";
            seekBaseMs = 0;
            startDecode(gen, bytes, 0);
        } catch (Throwable e) {
            log.accept(track + ": " + e);
            fail(gen, "加载失败：" + describe(e));
        }
    }

    /** An ID3 tag, or an MPEG frame header within the first few KB. */
    static boolean looksLikeMp3(byte[] b) {
        if (b.length > 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;
        for (int i = 0; i + 1 < Math.min(b.length, 8192); i++) if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xE0) == 0xE0) return true;
        return false;
    }

    private void fail(int gen, String msg) {
        if (gen != generation) return;
        state = State.ERROR;
        message = msg;
        // a song that cannot play is skipped after a moment (once round the list at most)
        if (++failsInRow >= queue.size()) {
            if (queue.size() > 1) message = "这个列表里的歌都放不了（" + msg + "）";
            return;
        }
        loader.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            if (gen == generation && queue.size() > 1) next();
        });
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.trim().isEmpty() ? "" : ": " + (m.length() > 60 ? m.substring(0, 60) : m));
    }

    private void startDecode(int gen, byte[] bytes, long startMs) {
        Thread t = new Thread(() -> decodeLoop(gen, bytes, startMs), "Gale NetEase decode");
        t.setDaemon(true);
        t.start();
    }

    private void decodeLoop(int gen, byte[] bytes, long startMs) {
        Sink out = null;
        boolean finished = false;
        int frames = 0;
        try {
            Bitstream bitstream = new Bitstream(new ByteArrayInputStream(bytes));
            Decoder decoder = new Decoder();
            // skipping to the start: frames are read, not decoded
            float skipped = 0;
            Header h;
            while (skipped < startMs && (h = bitstream.readFrame()) != null) {
                skipped += h.ms_per_frame();
                bitstream.closeFrame();
            }
            if (gen != generation) return;
            seekBaseMs = (long) skipped;
            synchronized (beats) {
                beats.clear();
            }
            float decodedMs = skipped, lowPass = 0, avgEnergy = 0, lastBeatMs = -1000;
            while (gen == generation) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    finished = true;
                    break;
                }
                SampleBuffer sb;
                try {
                    sb = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                } catch (Exception e) {
                    bitstream.closeFrame();
                    continue;
                }
                int ch = Math.max(1, sb.getChannelCount());
                if (out == null) {
                    AudioFormat format = new AudioFormat(header.frequency(), 16, ch, true, false);
                    out = silent ? new SilentSink(format) : new LineSink(format, header.frequency() * ch * 2 / 4);
                    sink = out;
                    failsInRow = 0;
                    if (state == State.PAUSED) {
                        out.pause();
                    } else {
                        out.resume();
                        state = State.PLAYING;
                        if (message.equals("加载中…")) message = "";
                    }
                }
                while (state == State.PAUSED && gen == generation) Thread.sleep(15);
                if (gen != generation) break;
                int len = sb.getBufferLength();
                short[] pcm = sb.getBuffer();
                float frameMs = header.ms_per_frame();
                // beats: the bass (a slow low-pass of the mono signal) jumping well above its running average
                float energy = 0;
                for (int k = 0; k + ch - 1 < len; k += ch) {
                    float mono = 0;
                    for (int c = 0; c < ch; c++) mono += pcm[k + c];
                    mono /= ch * 32768f;
                    lowPass += (mono - lowPass) * 0.04f;
                    energy += lowPass * lowPass;
                }
                energy /= Math.max(1, len / ch);
                if (avgEnergy > 0 && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170) {
                    float strength = Math.max(0, Math.min(1, (energy / avgEnergy - 1.7f) / 2f + 0.45f));
                    synchronized (beats) {
                        beats.addLast(new long[]{ (long) decodedMs, Float.floatToIntBits(strength) });
                        while (beats.size() > 48) beats.removeFirst();
                    }
                    lastBeatMs = decodedMs;
                }
                avgEnergy = avgEnergy == 0 ? energy : avgEnergy + (energy - avgEnergy) * Math.min(frameMs / 1000f, 1f);
                decodedMs += frameMs;
                float v = volume;
                byte[] data = new byte[len * 2];
                for (int k = 0; k < len; k++) {
                    int s = (int) (pcm[k] * v);
                    data[k * 2] = (byte) s;
                    data[k * 2 + 1] = (byte) (s >> 8);
                }
                out.write(data);
                frames++;
                bitstream.closeFrame();
            }
            if (finished && gen == generation && out != null) out.drain();
        } catch (Throwable e) {
            if (gen == generation) {
                log.accept(current + ": decoding failed: " + e);
                state = State.ERROR;
                message = "播放失败：" + describe(e);
            }
        } finally {
            if (sink == out) sink = null;
            if (out != null) out.close();
        }
        if (finished && gen == generation && frames == 0 && startMs < durationMs() - 2000) {
            log.accept(current + ": no sound in the file");
            fail(gen, "这首歌放不出来（文件里没有声音）");
            return;
        }
        if (finished && gen == generation) {
            if (repeat == Repeat.ONE) playIndex(index);
            else if (repeat == Repeat.OFF && !shuffle && index >= queue.size() - 1) {
                pausedAtMs = durationMs();
                state = State.PAUSED;
            } else next();
        }
    }

    private void stopSink() {
        Sink s = sink;
        sink = null;
        if (s != null) s.close();
    }

    // ------------------------------------------------------------------ where the sound goes

    private interface Sink {
        void write(byte[] data) throws Exception;

        void pause();

        void resume();

        long positionUs();

        void drain();

        void close();
    }

    /** The sound card (the default device, or the first that takes the format). */
    private static final class LineSink implements Sink {
        private final SourceDataLine line;

        LineSink(AudioFormat format, int bufferBytes) throws Exception {
            line = open(format, bufferBytes);
        }

        private static SourceDataLine open(AudioFormat format, int bufferBytes) throws Exception {
            Exception last;
            try {
                SourceDataLine l = AudioSystem.getSourceDataLine(format);
                try {
                    l.open(format, bufferBytes);
                } catch (Exception e) {
                    l.open(format);
                }
                return l;
            } catch (Exception e) {
                last = e;
            }
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (!mixer.isLineSupported(info)) continue;
                    SourceDataLine l = (SourceDataLine) mixer.getLine(info);
                    l.open(format);
                    return l;
                } catch (Exception e) {
                    last = e;
                }
            }
            throw last != null ? last : new IllegalStateException("没有可用的声音输出设备");
        }

        @Override
        public void write(byte[] data) { line.write(data, 0, data.length); }

        @Override
        public void pause() { line.stop(); }

        @Override
        public void resume() { line.start(); }

        @Override
        public long positionUs() { return line.getMicrosecondPosition(); }

        @Override
        public void drain() { line.drain(); }

        @Override
        public void close() {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** No sound: takes the samples at the speed they would be heard, a quarter second ahead, like a real line. */
    private static final class SilentSink implements Sink {
        private final double bytesPerUs;
        private long written, startedAt, pausedTotal, pausedAt = -1;
        private volatile boolean closed;

        SilentSink(AudioFormat f) { bytesPerUs = f.getSampleRate() * f.getChannels() * 2 / 1e6; }

        private synchronized long playedUs() {
            long now = System.nanoTime() / 1000;
            long end = pausedAt >= 0 ? pausedAt : now;
            return Math.min((long) (written / bytesPerUs), Math.max(0, end - startedAt - pausedTotal));
        }

        @Override
        public void write(byte[] data) throws InterruptedException {
            synchronized (this) {
                written += data.length;
            }
            while (!closed && written / bytesPerUs - playedUs() > 250_000) Thread.sleep(5);
        }

        @Override
        public synchronized void pause() {
            if (startedAt == 0) startedAt = System.nanoTime() / 1000;
            if (pausedAt < 0) pausedAt = System.nanoTime() / 1000;
        }

        @Override
        public synchronized void resume() {
            long now = System.nanoTime() / 1000;
            if (startedAt == 0) startedAt = now;
            if (pausedAt >= 0) {
                pausedTotal += now - pausedAt;
                pausedAt = -1;
            }
        }

        @Override
        public long positionUs() { return playedUs(); }

        @Override
        public void drain() {
            try {
                while (!closed && playedUs() < written / bytesPerUs) Thread.sleep(5);
            } catch (InterruptedException ignored) {
            }
        }

        @Override
        public void close() { closed = true; }
    }
}
