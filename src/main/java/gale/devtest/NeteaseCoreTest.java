package gale.devtest;

import gale.lyrics.LyricLine;
import gale.lyrics.LyricParser;
import gale.lyrics.Lyrics;
import gale.netease.NeteaseApi;
import gale.netease.NeteasePlayer;
import gale.netease.NeteaseLogin;
import gale.lyrics.LyricsProvider;
import gale.netease.model.NeteasePlaylist;
import gale.netease.model.NeteaseTrack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * gradlew neteaseCoreTest: NetEase Cloud Music and the lyrics without the game. Offline: the weapi encryption, LRC and
 * YRC, splitting joined lines, the placeholders, matching names, the QR code. Online (needs the internet; no account):
 * NetEase search, charts, playlists, lyrics, the encrypted login request being accepted, LRCLIB, finding a Spotify
 * song's lyrics by name, and a free song played start to end of a few seconds with no sound (position, pause, seek,
 * beats).
 */
public final class NeteaseCoreTest {
    private static int passed, failed;

    private NeteaseCoreTest() { }

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : "  (" + detail + ")"));
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return c.getAsBoolean();
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("build/coretest");
        dir.mkdirs();
        File cookie = new File(dir, "netease.json");
        cookie.delete();
        NeteaseApi.cookieFile = cookie;
        try {
            offline();
            online();
        } catch (Throwable t) {
            t.printStackTrace();
            check("no exception", false, t);
        }
        NeteasePlayer.INSTANCE.stop();
        System.out.println("=== " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void offline() throws Exception {
        System.out.println("--- weapi");
        String rsa = NeteaseApi.rsa("abcdefghijklmnop");
        check("RSA key is 256 hex digits", rsa.length() == 256 && rsa.matches("[0-9a-f]+"), rsa.length());
        check("RSA is deterministic", rsa.equals(NeteaseApi.rsa("abcdefghijklmnop")), null);
        // AES-128-CBC, key 0CoJUm6Qyw8W8jud, iv 0102030405060708, of {"a":1}
        String aes = NeteaseApi.aes("{\"a\":1}", "0CoJUm6Qyw8W8jud");
        check("AES is base64 of one block", aes.length() == 24 && aes.endsWith("=="), aes);
        String body = NeteaseApi.weapiBody("{\"type\":1}");
        check("weapi body has params and encSecKey", body.startsWith("params=") && body.contains("&encSecKey=") && body.length() > 300, body.length());
        check("keeps only the login cookies", NeteaseApi.keepCookies(Arrays.asList("MUSIC_U=abc; Path=/; HttpOnly", "__csrf=x1; Path=/",
            "JSESSIONID=nope; Path=/", "MUSIC_A_T=; Path=/")).equals("MUSIC_U=abc; __csrf=x1"), null);

        System.out.println("--- LRC");
        List<LyricLine> lrc = LyricParser.lrc("[ti:Test]\n[00:01.50]第一句\n[00:03.2]second line\n[00:05.123][01:00.00]repeated\n作词：nobody\n"
            + "[00:07.00]作曲 : 某人\n[00:08.00]\n[00:09.00]纯音乐，请欣赏");
        check("LRC lines in time order", lrc.size() == 6 && lrc.get(0).timeMs == 1500 && lrc.get(1).timeMs == 3200 && lrc.get(2).timeMs == 5123, lrc);
        check("a line with two times appears twice", lrc.get(lrc.size() - 1).text.equals("repeated") && lrc.get(lrc.size() - 1).timeMs == 60000, null);
        check("credit lines dropped", lrc.stream().noneMatch(l -> l.text.contains("作曲") || l.text.contains("作词")), null);
        Lyrics withPlaceholder = new Lyrics(lrc, "test");
        check("placeholder dropped, blank kept", withPlaceholder.lines().stream().noneMatch(l -> l.text.contains("纯音乐"))
            && withPlaceholder.lines().stream().anyMatch(l -> l.text.isEmpty()), withPlaceholder.lines());
        check("indexAt", withPlaceholder.indexAt(0) == -1 && withPlaceholder.indexAt(1500) == 0 && withPlaceholder.indexAt(3300) == 1
            && withPlaceholder.indexAt(999999) == withPlaceholder.lines().size() - 1, null);
        check("all-placeholder lyrics are empty", new Lyrics(LyricParser.lrc("[00:01.00]纯音乐，请欣赏"), "t").isEmpty(), null);

        System.out.println("--- YRC");
        List<LyricLine> yrc = LyricParser.yrc("{\"t\":0,\"c\":[{\"tx\":\"作词: \"}]}\n[1000,2000](1000,300,0)我(1300,300,0)们 (1600,400,0)hello(2000,500,0) world\n"
            + "[5000,1000](5000,500,0)作词：(5500,500,0)某人");
        check("YRC words and their times", yrc.size() == 1 && yrc.get(0).words.size() == 4 && yrc.get(0).words.get(2).startMs == 1600
            && yrc.get(0).text.equals("我们 hello world"), yrc);

        System.out.println("--- splitting joined lines");
        Lyrics.splitLines = true;
        Lyrics joined = new Lyrics(LyricParser.lrc("[00:10.00]我们的歌 你在听吗\n[00:16.00]next"), "t");
        List<LyricLine> split = joined.lines();
        check("two CJK halves become two lines", split.size() == 3 && split.get(0).text.equals("我们的歌") && split.get(1).text.equals("你在听吗"), split);
        check("the second half starts later", split.size() == 3 && split.get(1).timeMs > 10000 && split.get(1).timeMs < 16000, split);
        Lyrics english = new Lyrics(LyricParser.lrc("[00:10.00]hello there my friend\n[00:16.00]next"), "t");
        check("Latin spaces do not split", english.lines().size() == 2, english.lines());
        Lyrics shortHalf = new Lyrics(LyricParser.lrc("[00:10.00]啊 你在听吗\n[00:16.00]next"), "t");
        check("a one-character half is kept with its line", shortHalf.lines().size() == 2, shortHalf.lines());
        List<LyricLine> yrcJoined = LyricParser.yrc("[1000,4000](1000,300,0)我(1300,300,0)们(1600,300,0)的(1900,300,0)歌 (2600,300,0)你(2900,300,0)在(3200,300,0)听(3500,300,0)吗");
        List<LyricLine> yrcSplit = new Lyrics(yrcJoined, "t").lines();
        check("word-timed halves start at their first word", yrcSplit.size() == 2 && yrcSplit.get(1).timeMs == 2600 && yrcSplit.get(1).words.size() == 4, yrcSplit);
        Lyrics.splitLines = false;
        check("splitting can be turned off", joined.lines().size() == 2, null);
        Lyrics.splitLines = true;

        System.out.println("--- matching names");
        check("bare title", LyricsProvider.bare("Bohemian Rhapsody - Remastered 2011").equals("Bohemian Rhapsody")
            && LyricsProvider.bare("Song (feat. Someone)").equals("Song") && LyricsProvider.bare("晴天（Live）").equals("晴天"), null);
        check("first artist", LyricsProvider.firstArtist("Daft Punk, Pharrell Williams").equals("Daft Punk") && LyricsProvider.firstArtist("周杰伦 / 费玉清").equals("周杰伦"), null);
        check("CJK detection", LyricsProvider.hasCjk("晴天") && !LyricsProvider.hasCjk("Blinding Lights"), null);

        System.out.println("--- QR code");
        BufferedImage qr = NeteaseLogin.makeQr("https://music.163.com/login?codekey=test-key", 256);
        check("QR code picture", qr != null && qr.getWidth() == 256 && (qr.getRGB(2, 2) & 0xFFFFFF) == 0xFFFFFF, qr == null ? null : qr.getWidth());

        System.out.println("--- track parsing");
        NeteaseTrack t = NeteaseTrack.parse(new com.google.gson.JsonParser().parse("{\"id\":186016,\"name\":\"晴天\",\"ar\":[{\"name\":\"周杰伦\"}],\"al\":{\"name\":\"叶惠美\","
            + "\"picUrl\":\"http://p1.music.126.net/x.jpg\"},\"dt\":269000,\"fee\":1}").getAsJsonObject());
        check("new style song", t != null && t.id == 186016 && t.artists.equals("周杰伦") && t.durationMs == 269000 && t.vip() && t.cover(64).endsWith("?param=64y64"), t);
        NeteaseTrack old = NeteaseTrack.parse(new com.google.gson.JsonParser().parse("{\"id\":\"5\",\"name\":\"x\",\"artists\":[{\"name\":\"a\"},{\"name\":\"b\"}],"
            + "\"album\":{\"name\":\"al\"},\"duration\":1000}").getAsJsonObject());
        check("old style song", old != null && old.artists.equals("a / b") && old.durationMs == 1000 && !old.vip(), old);
    }

    private static void online() throws Exception {
        System.out.println("--- NetEase online");
        List<NeteaseTrack> found = NeteaseApi.search("周杰伦 晴天", 10);
        check("search finds songs", !found.isEmpty(), found.isEmpty() ? NeteaseApi.lastSearchError : found.get(0));
        check("search results have covers and lengths", !found.isEmpty() && !found.get(0).cover.isEmpty() && found.get(0).durationMs > 0, null);
        String[] texts = NeteaseApi.lyricTexts(186016);
        check("lyrics of 晴天 (LRC)", texts[0] != null && texts[0].contains("["), texts[0] == null ? null : texts[0].length());
        Lyrics qt = gale.lyrics.LyricsProvider.netease(186016);
        check("parsed lyrics of 晴天", qt != null && qt.lines().size() > 20, qt == null ? null : qt.source + " " + qt.lines().size() + " lines"
            + (texts[1] != null ? ", word-timed" : ", line-timed"));
        Lyrics wordTimed = LyricsProvider.netease(1973665667L);
        check("word-timed lyrics (YRC) when NetEase has them", wordTimed != null && wordTimed.lines().stream().anyMatch(l -> l.words != null && l.words.size() > 2),
            wordTimed == null ? null : wordTimed.lines().size() + " lines");
        List<NeteasePlaylist> charts = NeteaseApi.toplists();
        check("charts", charts.size() > 5, charts.size());
        List<NeteasePlaylist> recommended = NeteaseApi.recommendPlaylists();
        check("recommended playlists", recommended.size() > 5, recommended.size());
        List<NeteaseTrack> chart = charts.isEmpty() ? null : NeteaseApi.playlistTracks(charts.get(0).id);
        check("a chart's songs", chart != null && chart.size() >= 50, chart == null ? null : charts.get(0).name + ": " + chart.size());
        NeteaseApi.Weapi key = NeteaseApi.weapi(NeteaseApi.base + "/weapi/login/qrcode/unikey", "{\"type\":1}", null);
        check("NetEase accepts the encrypted login request", key != null && key.body.contains("unikey"), key == null ? null : key.body);
        Lyrics byName = LyricsProvider.neteaseSearch("晴天", "周杰伦", 269000);
        check("a Spotify song's lyrics found on NetEase by name and length", byName != null && byName.lines().size() > 20, byName == null ? null : byName.lines().size());

        System.out.println("--- LRCLIB online");
        Lyrics lrclib = LyricsProvider.lrclib("Blinding Lights", "The Weeknd", "After Hours", 200040);
        check("LRCLIB synced lyrics", lrclib != null && lrclib.lines().size() > 20 && lrclib.source.equals("LRCLIB"), lrclib == null ? null : lrclib.lines().size());

        System.out.println("--- playing a free song, no sound");
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        NeteasePlayer.silent = true;
        p.bitrate = 128000;
        List<NeteaseTrack> songs = NeteaseApi.search("纯音乐 钢琴", 20);
        check("found songs to play", songs.size() > 3, songs.size());
        if (songs.isEmpty()) return;
        p.playQueue(songs, 0);
        boolean playing = waitFor(() -> p.state == NeteasePlayer.State.PLAYING, 90000);
        check("a song plays (the unplayable ones skipped)", playing, p.current + " " + p.message);
        if (!playing) return;
        long t0 = p.positionMs();
        Thread.sleep(2000);
        long t1 = p.positionMs();
        check("the position moves at real speed", t1 - t0 > 1600 && t1 - t0 < 2500, (t1 - t0) + " ms in 2 s");
        float beat = 0;
        long until = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < until) {
            beat = Math.max(beat, p.beatPulse(p.positionMs()));
            Thread.sleep(15);
        }
        check("beats heard in 6 s", beat > 0.1f, beat);
        p.togglePause();
        long a = p.positionMs();
        Thread.sleep(800);
        check("paused: the position stays", p.state == NeteasePlayer.State.PAUSED && Math.abs(p.positionMs() - a) < 30, a + " -> " + p.positionMs());
        p.togglePause();
        Thread.sleep(600);
        check("resumed", p.state == NeteasePlayer.State.PLAYING && p.positionMs() > a + 300, p.positionMs() - a);
        long target = Math.min(60000, p.durationMs() / 2);
        p.seek(target);
        waitFor(() -> Math.abs(p.positionMs() - target) < 1500 && p.state == NeteasePlayer.State.PLAYING, 4000);
        check("seek", Math.abs(p.positionMs() - target) < 1500, p.positionMs() + " vs " + target);
        int before = p.index;
        p.next();
        check("next starts loading the next song", p.index == (before + 1) % p.queue.size() && p.state == NeteasePlayer.State.LOADING, p.index);
        p.stop();
        check("stopped", p.state == NeteasePlayer.State.STOPPED && p.current == null, null);
    }
}
