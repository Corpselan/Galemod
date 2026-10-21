package gale.netease.ui;

import gale.lyrics.LyricLine;
import gale.lyrics.Lyrics;
import gale.lyrics.LyricsModule;
import gale.netease.NeteaseApi;
import gale.netease.NeteaseLogin;
import gale.netease.NeteaseModule;
import gale.netease.NeteasePlayer;
import gale.netease.model.NeteasePlaylist;
import gale.netease.model.NeteaseTrack;
import gale.spotify.SpotifyModule;
import gale.spotify.ui.Covers;
import gale.spotify.ui.Icons;
import gale.spotify.ui.SpotifyScreen;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The NetEase player, laid out like the Spotify one (they switch with the pill at the top left): on the left now
 * playing, discover (recommended playlists), my playlists, charts and search, and your playlists by name; in the
 * middle the list; at the bottom the controls, the position (drag to seek) and the volume. Left click a song plays the
 * list from it, right click plays it next. Space plays / pauses, arrows seek and change the volume.
 */
public final class NeteaseScreen extends GuiScreen {
    public enum Section { NOW, DISCOVER, MINE, CHARTS, PLAYLIST, SEARCH, LOGIN }

    static final int RED = 0xFFEC4141, BG = 0xF5141418, SIDE = 0xFF0C0C0E, BAR = 0xFF1B1B20, TEXT = 0xFFFFFFFF, SUB = 0xFFB3B3B3,
        DIM = 0xFF6A6A6A, HOVER = 0x14FFFFFF, FIELD = 0xFF26262C, WARN = 0xFFE8A33C;
    private static final float SIDE_W = 128, BAR_H = 56, ROW = 20;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Gale NetEase lists");
        t.setDaemon(true);
        return t;
    });
    /** kept between openings (they change slowly) */
    private static volatile List<NeteasePlaylist> recommended, charts, mine;

    private final NeteaseModule module;
    private Section section;
    private volatile List<NeteaseTrack> tracks;
    private volatile boolean loading;
    private volatile String loadError = "";
    private NeteasePlaylist playlist;
    private String query = "";
    private boolean typing;
    private float scroll, maxScroll, sideScroll, sideMax;
    private float scale = 1f, vw, vh;
    private final List<Hit> hits = new ArrayList<>();
    private String drag;
    private float dragValue;
    private String toast = "";
    private long toastAt;
    private float px, py, pw, ph, sideTop, sideBottom;
    private ResourceLocation qrTexture;
    private int qrShown = -1;

    private static final class Hit {
        final String id;
        final float x, y, w, h;
        final Runnable left, right;

        Hit(String id, float x, float y, float w, float h, Runnable left, Runnable right) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.left = left;
            this.right = right;
        }

        boolean over(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    public NeteaseScreen(NeteaseModule module) { this(module, Section.NOW); }

    public NeteaseScreen(NeteaseModule module, Section section) {
        this.module = module;
        this.section = section;
    }

    private NeteasePlayer player() { return module.music(); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (NeteaseApi.loggedIn() && mine == null) loadMine();
        open(section);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (NeteaseLogin.state != NeteaseLogin.State.SUCCESS) NeteaseLogin.cancel();
        if (qrTexture != null) mc.getTextureManager().deleteTexture(qrTexture);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------ what is shown (also for the tests)

    public Section section() { return section; }

    public List<NeteaseTrack> tracks() { return tracks; }

    public boolean loading() { return loading; }

    public String toast() { return toast; }

    public static List<NeteasePlaylist> mine() { return mine; }

    public void open(Section s) {
        section = s;
        scroll = 0;
        typing = s == Section.SEARCH;
        switch (s) {
            case DISCOVER:
                if (recommended == null) lists(NeteaseApi::recommendPlaylists, l -> recommended = l);
                break;
            case CHARTS:
                if (charts == null) lists(NeteaseApi::toplists, l -> charts = l);
                break;
            case MINE:
                if (!NeteaseApi.loggedIn()) {
                    open(Section.LOGIN);
                    return;
                }
                if (mine == null) loadMine();
                break;
            case LOGIN:
                if (NeteaseLogin.state != NeteaseLogin.State.WAITING && NeteaseLogin.state != NeteaseLogin.State.SCANNED
                    && NeteaseLogin.state != NeteaseLogin.State.LOADING) NeteaseLogin.start();
                break;
            case SEARCH:
                if (!query.isEmpty() && tracks == null) search(query);
                break;
            default:
                break;
        }
    }

    private interface Call<T> {
        T get() throws Exception;
    }

    private void lists(Call<List<NeteasePlaylist>> call, java.util.function.Consumer<List<NeteasePlaylist>> done) {
        loading = true;
        LOADER.execute(() -> {
            try {
                done.accept(call.get());
                loadError = "";
            } catch (Exception e) {
                loadError = "加载失败：" + e.getClass().getSimpleName();
                done.accept(new ArrayList<>());
            } finally {
                loading = false;
            }
        });
    }

    private void loadMine() { lists(NeteaseApi::userPlaylists, l -> mine = l); }

    private void loadTracks(Supplier<List<NeteaseTrack>> call) {
        tracks = null;
        loading = true;
        LOADER.execute(() -> {
            try {
                tracks = call.get();
            } catch (Exception e) {
                tracks = new ArrayList<>();
                loadError = "加载失败：" + e.getClass().getSimpleName();
            } finally {
                loading = false;
            }
        });
    }

    public void openPlaylist(NeteasePlaylist p) {
        playlist = p;
        section = Section.PLAYLIST;
        scroll = 0;
        typing = false;
        loadTracks(() -> {
            try {
                return NeteaseApi.playlistTracks(p.id);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void search(String q) {
        query = q;
        section = Section.SEARCH;
        scroll = 0;
        if (q.trim().isEmpty()) return;
        String keyword = q.trim();
        loadTracks(() -> {
            List<NeteaseTrack> l = NeteaseApi.search(keyword, 50);
            if (l.isEmpty() && !NeteaseApi.lastSearchError.isEmpty()) loadError = "搜索失败：" + NeteaseApi.lastSearchError;
            return l;
        });
    }

    private void say(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
    }

    /** Clicks what has this id (as the mouse would, at its middle); false if it is not on screen. */
    public boolean click(String id, int button) {
        for (Hit h : hits) {
            if (!h.id.equals(id)) continue;
            clickAt(h.x + h.w / 2, h.y + h.h / 2, button);
            return true;
        }
        return false;
    }

    public List<String> hitIds() {
        List<String> out = new ArrayList<>();
        for (Hit h : hits) out.add(h.id);
        return out;
    }

    // ------------------------------------------------------------------ drawing

    private void hit(String id, float x, float y, float w, float h, Runnable left, Runnable right) { hits.add(new Hit(id, x, y, w, h, left, right)); }

    private static boolean over(int mx, int my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private void clip(float x, float y, float w, float h) { RenderUtil.scissor(x * scale, y * scale, w * scale, h * scale); }

    private void measure() {
        int factor = new ScaledResolution(mc).getScaleFactor();
        scale = Math.min(2f / factor, height / 300f);
        vw = width / scale;
        vh = height / scale;
    }

    @Override
    public void drawScreen(int gmx, int gmy, float pt) {
        hits.clear();
        drawRect(0, 0, width, height, 0x99000000);
        measure();
        int mx = (int) (gmx / scale), my = (int) (gmy / scale);
        if (section == Section.LOGIN && NeteaseLogin.state == NeteaseLogin.State.SUCCESS) {
            loadMine();
            open(Section.MINE);
            say("登录成功");
        }
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1f);
        pw = Math.min(540, vw - 20);
        ph = Math.min(330, vh - 16);
        px = (vw - pw) / 2f;
        py = (vh - ph) / 2f;
        RoundRect.draw(px, py, px + pw, py + ph, 10, BG);
        sidebar(mx, my);
        float cx = px + SIDE_W + 10, cy = py + 10, cw = pw - SIDE_W - 20, ch = ph - BAR_H - 18;
        content(cx, cy, cw, ch, mx, my);
        bar(mx, my);
        String msg = player().message;
        if (!msg.isEmpty() && player().state != NeteasePlayer.State.LOADING) banner(msg, WARN);
        else if (!loadError.isEmpty() && (section == Section.SEARCH || section == Section.PLAYLIST)) banner(loadError, WARN);
        else if (!toast.isEmpty() && System.currentTimeMillis() - toastAt < 2500) banner(toast, RED);
        GlStateManager.popMatrix();
    }

    private void banner(String s, int color) {
        String text = Icons.fit(s, pw - SIDE_W - 40);
        float w = Icons.width(text) + 16, x = px + SIDE_W + 10 + (pw - SIDE_W - 20 - w) / 2f, y = py + ph - BAR_H - 22;
        RoundRect.draw(x, y, x + w, y + 14, 7, color);
        Icons.text(text, x + 8, y + 3, 0xFF000000);
    }

    /** The pill that switches to the other player; shared look with the Spotify screen. */
    public static void sourcePill(String label, int color, float x, float y, boolean hover) {
        float w = Icons.width(label) + 10;
        RoundRect.draw(x - w, y, x, y + 11, 5.5f, hover ? color : 0x33FFFFFF);
        Icons.text(label, x - w + 5, y + 2, hover ? 0xFF000000 : 0xFFDDDDDD);
    }

    public static float pillWidth(String label) { return Icons.width(label) + 10; }

    private void sidebar(int mx, int my) {
        float x = px + 5, y = py + 5, w = SIDE_W - 2, h = ph - BAR_H - 12;
        RoundRect.draw(x, y, x + w, y + h, 8, SIDE);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 9, y + 9, 0);
        GlStateManager.scale(1.2f, 1.2f, 1f);
        Icons.text("网易云音乐", 0, 0, RED);
        GlStateManager.popMatrix();
        float pillR = x + w - 6, pw2 = pillWidth("Spotify");
        boolean pillHover = over(mx, my, pillR - pw2, y + 8, pw2, 11);
        sourcePill("Spotify", 0xFF1DB954, pillR, y + 8, pillHover);
        hit("source:spotify", pillR - pw2, y + 8, pw2, 11, this::toSpotify, null);
        String who = NeteaseApi.loggedIn() ? (NeteaseApi.nickname.isEmpty() ? "已登录" : NeteaseApi.nickname) : "未登录 · 点我的歌单扫码";
        Icons.text(Icons.fit(who, w - 18), x + 9, y + 24, DIM);
        Object[][] nav = { { Section.NOW, "正在播放" }, { Section.DISCOVER, "推荐歌单" }, { Section.MINE, "我的歌单" }, { Section.CHARTS, "排行榜" },
            { Section.SEARCH, "搜索" } };
        float ny = y + 40;
        for (Object[] n : nav) {
            Section s = (Section) n[0];
            boolean on = section == s || s == Section.MINE && section == Section.LOGIN, hover = over(mx, my, x, ny, w, 16);
            if (on) RoundRect.draw(x + 4, ny, x + w - 4, ny + 16, 4, 0x22FFFFFF);
            navIcon(s, x + 15, ny + 8, on ? RED : hover ? TEXT : SUB);
            Icons.text((String) n[1], x + 26, ny + 4, on || hover ? TEXT : SUB);
            hit("nav:" + s.name().toLowerCase(), x, ny, w, 16, () -> open(s), null);
            ny += 18;
        }
        RenderUtil.rect(x + 9, ny + 3, x + w - 9, ny + 3.5f, 0x22FFFFFF);
        ny += 8;
        List<NeteasePlaylist> lists = NeteaseApi.loggedIn() ? mine : null;
        sideTop = ny;
        sideBottom = y + h - 4;
        if (lists == null) return;
        sideMax = Math.max(0, lists.size() * 13 - (sideBottom - sideTop));
        sideScroll = Math.max(0, Math.min(sideScroll, sideMax));
        clip(x, sideTop, w, sideBottom - sideTop);
        for (int i = 0; i < lists.size(); i++) {
            NeteasePlaylist p = lists.get(i);
            float ly = sideTop + i * 13 - sideScroll;
            if (ly + 13 < sideTop || ly > sideBottom) continue;
            boolean on = section == Section.PLAYLIST && playlist != null && p.id == playlist.id;
            boolean hover = over(mx, my, x, ly, w, 13) && my >= sideTop && my < sideBottom;
            Icons.text(Icons.fit(p.name, w - 18), x + 9, ly + 2, on ? RED : hover ? TEXT : SUB);
            if (ly >= sideTop - 1 && ly + 13 <= sideBottom + 1) hit("side:playlist:" + i, x, ly, w, 13, () -> openPlaylist(p), null);
        }
        RenderUtil.endScissor();
    }

    private void toSpotify() {
        SpotifyModule s = SpotifyModule.instance;
        if (s == null) return;
        if (!s.isEnabled()) s.setEnabled(true);
        mc.displayGuiScreen(new SpotifyScreen(s));
    }

    private void navIcon(Section s, float cx, float cy, int c) {
        switch (s) {
            case NOW: Icons.note(cx, cy, 9, c); break;
            case DISCOVER:
                RoundRect.circle(cx, cy, 4, c);
                RoundRect.circle(cx, cy, 2.6f, SIDE);
                RoundRect.circle(cx, cy, 1.1f, c);
                break;
            case MINE: Icons.heart(cx, cy, 8, c); break;
            case CHARTS:
                for (int i = 0; i < 3; i++) RoundRect.draw(cx - 4 + i * 3, cy + 3.5f - (2 + i * 2.2f), cx - 2.2f + i * 3, cy + 3.5f, 0.4f, c);
                break;
            default:
                RoundRect.circle(cx - 1, cy - 1, 3.2f, c);
                RoundRect.circle(cx - 1, cy - 1, 2.1f, SIDE);
                Icons.line(cx + 1.2f, cy + 1.2f, cx + 3.8f, cy + 3.8f, 1.3f, c);
                break;
        }
    }

    private void content(float x, float y, float w, float h, int mx, int my) {
        switch (section) {
            case NOW: now(x, y, w, h, mx, my); break;
            case DISCOVER: grid("推荐歌单", recommended, x, y, w, h, mx, my); break;
            case MINE: grid("我的歌单", mine, x, y, w, h, mx, my); break;
            case CHARTS: grid("排行榜", charts, x, y, w, h, mx, my); break;
            case PLAYLIST: trackList(playlist == null ? "" : playlist.name, x, y, w, h, mx, my); break;
            case SEARCH: searchView(x, y, w, h, mx, my); break;
            case LOGIN: login(x, y, w, h, mx, my); break;
        }
    }

    private void title(String t, String sub, float x, float y) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(1.5f, 1.5f, 1f);
        Icons.text(t, 0, 0, TEXT);
        GlStateManager.popMatrix();
        if (sub != null) Icons.text(sub, x, y + 16, DIM);
    }

    private void login(float x, float y, float w, float h, int mx, int my) {
        float cx = x + w / 2f;
        String head = "扫码登录网易云音乐";
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, y + 6, 0);
        GlStateManager.scale(1.4f, 1.4f, 1f);
        Icons.text(head, -Icons.width(head) / 2f, 0, TEXT);
        GlStateManager.popMatrix();
        NeteaseLogin.State st = NeteaseLogin.state;
        float size = Math.min(110, h - 70), qx = cx - size / 2f, qy = y + 24;
        RoundRect.draw(qx - 4, qy - 4, qx + size + 4, qy + size + 4, 6, 0xFFFFFFFF);
        if (NeteaseLogin.qr != null && (st == NeteaseLogin.State.WAITING || st == NeteaseLogin.State.SCANNED)) {
            drawQr(qx, qy, size);
            if (st == NeteaseLogin.State.SCANNED) {
                RoundRect.draw(qx, qy, qx + size, qy + size, 4, 0xCC000000);
                String ok = "已扫码";
                Icons.text(ok, cx - Icons.width(ok) / 2f, qy + size / 2f - 4, TEXT);
            }
        } else {
            String s = st == NeteaseLogin.State.LOADING ? "加载中…" : st == NeteaseLogin.State.EXPIRED ? "二维码过期了" : st == NeteaseLogin.State.ERROR ? "出错了" : "";
            Icons.text(s, cx - Icons.width(s) / 2f, qy + size / 2f - 4, 0xFF333333);
        }
        String line = st == NeteaseLogin.State.SCANNED ? "在手机上点确认登录" : st == NeteaseLogin.State.EXPIRED ? "点下面刷新"
            : st == NeteaseLogin.State.ERROR ? Icons.fit(NeteaseLogin.error, w - 20) : "打开网易云音乐 App，扫一扫";
        float ly = qy + size + 10;
        Icons.text(line, cx - Icons.width(line) / 2f, ly, st == NeteaseLogin.State.ERROR ? WARN : SUB);
        String note = "不登录也能搜歌、听免费的歌和看歌单";
        Icons.text(note, cx - Icons.width(note) / 2f, ly + 12, DIM);
        if (st == NeteaseLogin.State.EXPIRED || st == NeteaseLogin.State.ERROR || st == NeteaseLogin.State.IDLE) {
            String label = "刷新二维码";
            float bw = Icons.width(label) + 24, bx = cx - bw / 2f, by = ly + 26;
            boolean hover = over(mx, my, bx, by, bw, 18);
            RoundRect.draw(bx, by, bx + bw, by + 18, 9, hover ? 0xFFF05454 : RED);
            Icons.text(label, cx - Icons.width(label) / 2f, by + 5, TEXT);
            hit("qr:refresh", bx, by, bw, 18, NeteaseLogin::start, null);
        }
    }

    private void drawQr(float x, float y, float size) {
        if (qrShown != NeteaseLogin.qrVersion || qrTexture == null) {
            if (qrTexture != null) mc.getTextureManager().deleteTexture(qrTexture);
            qrTexture = mc.getTextureManager().getDynamicTextureLocation("galeneteaseqr", new DynamicTexture(NeteaseLogin.qr));
            qrShown = NeteaseLogin.qrVersion;
        }
        mc.getTextureManager().bindTexture(qrTexture);
        // sharp squares, not blurred
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + size, 0).tex(0, 1).endVertex();
        wr.pos(x + size, y + size, 0).tex(1, 1).endVertex();
        wr.pos(x + size, y, 0).tex(1, 0).endVertex();
        wr.pos(x, y, 0).tex(0, 0).endVertex();
        t.draw();
    }

    private void now(float x, float y, float w, float h, int mx, int my) {
        NeteasePlayer p = player();
        NeteaseTrack t = p.current;
        if (t == null) {
            title("没有在放的歌", null, x + 4, y + 6);
            Icons.text("搜一首，或者从推荐歌单、排行榜里挑一首。", x + 4, y + 30, SUB);
            return;
        }
        float size = Math.min(100, h - 30);
        Covers.draw(module.covers, t.cover(300), x + 4, y + 6, size, 255);
        float tx = x + size + 16, tw = w - size - 20;
        GlStateManager.pushMatrix();
        GlStateManager.translate(tx, y + 10, 0);
        GlStateManager.scale(1.5f, 1.5f, 1f);
        Icons.text(Icons.fit(t.name, tw / 1.5f), 0, 0, TEXT);
        GlStateManager.popMatrix();
        Icons.text(Icons.fit(t.artists, tw), tx, y + 26, SUB);
        Icons.text(Icons.fit(t.album, tw), tx, y + 38, DIM);
        // the lyrics around the line playing, when the Lyrics module has them
        LyricsModule lm = LyricsModule.instance;
        Lyrics lyrics = lm != null && lm.isEnabled() ? lm.lyrics() : null;
        float ly = y + 54;
        if (lyrics != null && !lyrics.lines().isEmpty()) {
            List<LyricLine> lines = lyrics.lines();
            int at = lyrics.indexAt(p.positionMs() + (long) lm.offset.get());
            for (int i = Math.max(0, at - 1); i <= at + 4 && i < lines.size() && ly + 10 <= y + h; i++) {
                if (i < 0) continue;
                boolean cur = i == at;
                Icons.text(Icons.fit(lines.get(i).text, tw), tx, ly, cur ? RED : i < at ? DIM : SUB);
                ly += 12;
            }
            return;
        }
        List<NeteaseTrack> q = p.queue;
        Icons.text("播放列表 · " + q.size() + " 首", tx, ly, TEXT);
        ly += 12;
        int start = Math.max(0, Math.min(p.index, q.size() - 1));
        for (int i = start; i < q.size() && ly + 16 <= y + h; i++) {
            NeteaseTrack n = q.get(i);
            boolean hover = over(mx, my, tx, ly, tw, 16), cur = i == p.index;
            Covers.draw(module.covers, n.cover(64), tx, ly, 14, 255);
            Icons.text(Icons.fit(n.name + "  ·  " + n.artists, tw - 20), tx + 19, ly + 3, cur ? RED : hover ? TEXT : SUB);
            int index = i;
            hit("queue:" + i, tx, ly, tw, 16, () -> player().playIndex(index), null);
            ly += 17;
        }
    }

    private void grid(String name, List<NeteasePlaylist> l, float x, float y, float w, float h, int mx, int my) {
        title(name, l == null ? "加载中…" : l.size() + " 个", x + 4, y + 4);
        if (l == null) return;
        int cols = Math.max(1, (int) ((w - 4) / 96));
        float cw = (w - 4) / cols, top = y + 32, bottom = y + h;
        int rowsN = (l.size() + cols - 1) / cols;
        maxScroll = Math.max(0, rowsN * 112 - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        clip(x, top, w, bottom - top);
        for (int i = 0; i < l.size(); i++) {
            NeteasePlaylist p = l.get(i);
            float cx = x + 2 + (i % cols) * cw, cy = top + (i / cols) * 112 - scroll;
            boolean hover = over(mx, my, cx, cy, cw - 6, 106) && my >= top && my < bottom;
            RoundRect.draw(cx, cy, cx + cw - 6, cy + 106, 6, hover ? 0xFF2A2A30 : 0xFF1B1B20);
            Covers.draw(module.covers, NeteaseTrack.sized(p.cover, 160), cx + 6, cy + 6, Math.min(cw - 18, 72), 255);
            Icons.text(Icons.fit(p.name, cw - 18), cx + 6, cy + 82, TEXT);
            Icons.text(Icons.fit((p.owner.isEmpty() ? "" : p.owner + " · ") + p.tracks + " 首", cw - 18), cx + 6, cy + 93, SUB);
            if (cy >= top - 1 && cy + 106 <= bottom + 1) hit("playlist:" + i, cx, cy, cw - 6, 106, () -> openPlaylist(p), null);
        }
        RenderUtil.endScissor();
        if (l.isEmpty()) Icons.text(loadError.isEmpty() ? "这里还没有歌单" : loadError, x + 8, top + 6, DIM);
    }

    private void trackList(String name, float x, float y, float w, float h, int mx, int my) {
        List<NeteaseTrack> l = tracks;
        title(Icons.fit(name, (w - 40) / 1.5f), l == null ? (loading ? "加载中…" : "") : l.size() + " 首", x + 4, y + 4);
        if (l != null && !l.isEmpty()) {
            float bx = x + w - 22, by = y + 4;
            boolean hover = over(mx, my, bx - 9, by, 22, 22);
            RoundRect.circle(bx + 2, by + 9, 9, hover ? 0xFFF05454 : RED);
            Icons.play(bx + 2.5f, by + 9, 8, TEXT);
            hit("playall", bx - 9, by, 22, 20, () -> player().playQueue(l, 0), null);
        }
        rows(l, x, y + 32, w, h - 32, mx, my);
    }

    /** Song rows: number (or bars for the one playing), cover, name and singer (VIP marked), album, length. */
    private void rows(List<NeteaseTrack> l, float x, float y, float w, float h, int mx, int my) {
        if (l == null) return;
        Icons.text("#", x + 8, y, DIM);
        Icons.text("标题", x + 46, y, DIM);
        Icons.text("专辑", x + w * 0.62f, y, DIM);
        Icons.text("时长", x + w - 8 - Icons.width("时长"), y, DIM);
        RenderUtil.rect(x + 4, y + 11, x + w - 4, y + 11.5f, 0x22FFFFFF);
        float top = y + 14, bottom = y + h;
        maxScroll = Math.max(0, l.size() * ROW - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        NeteaseTrack playing = player().current;
        clip(x, top, w, bottom - top);
        for (int i = 0; i < l.size(); i++) {
            float ry = top + i * ROW - scroll;
            if (ry + ROW < top || ry > bottom) continue;
            NeteaseTrack t = l.get(i);
            boolean hover = over(mx, my, x, ry, w, ROW) && my >= top && my < bottom, current = playing != null && t.id == playing.id;
            if (hover) RoundRect.draw(x + 2, ry, x + w - 2, ry + ROW, 4, HOVER);
            if (current && player().state == NeteasePlayer.State.PLAYING) {
                long now = System.currentTimeMillis();
                for (int b = 0; b < 3; b++) {
                    float bh = 2 + 4 * (float) Math.abs(Math.sin(now / (170.0 + b * 37) + b));
                    RoundRect.draw(x + 7 + b * 3, ry + 13 - bh, x + 8.5f + b * 3, ry + 13, 0.4f, RED);
                }
            } else {
                Icons.text(String.valueOf(i + 1), x + 8, ry + 6, current ? RED : SUB);
            }
            Covers.draw(module.covers, t.cover(64), x + 24, ry + 2, 16, 255);
            float nameW = w * 0.62f - 52;
            // VIP songs, and songs this account cannot play at all (greyed)
            String tag = !t.canPlay() && !t.vip() ? "无版权" : t.vip() ? "VIP" : "";
            float nx = x + 46;
            if (!tag.isEmpty()) {
                RoundRect.draw(nx, ry + 2.5f, nx + Icons.width(tag) + 4, ry + 10.5f, 2, t.canPlay() ? 0x55EC4141 : 0x33FFFFFF);
                Icons.text(tag, nx + 2, ry + 2.5f, t.canPlay() ? RED : DIM);
                nx += Icons.width(tag) + 7;
            }
            Icons.text(Icons.fit(t.name, nameW - (nx - x - 46)), nx, ry + 2, current ? RED : t.canPlay() ? TEXT : DIM);
            Icons.text(Icons.fit(t.artists, nameW), x + 46, ry + 11, SUB);
            Icons.text(Icons.fit(t.album, w * 0.38f - 40), x + w * 0.62f, ry + 6, SUB);
            String d = Icons.time(t.durationMs);
            Icons.text(d, x + w - 8 - Icons.width(d), ry + 6, SUB);
            int index = i;
            if (ry >= top - 1 && ry + ROW <= bottom + 1) hit("row:" + i, x, ry, w, ROW, () -> player().playQueue(l, index), () -> {
                player().playNext(t);
                say("下一首播放：" + t.name);
            });
        }
        RenderUtil.endScissor();
        if (l.isEmpty()) Icons.text(loading ? "加载中…" : "这里还没有歌", x + 8, top + 6, DIM);
    }

    private void searchView(float x, float y, float w, float h, int mx, int my) {
        float fx = x + 4, fy = y + 4, fw = Math.min(260, w - 8);
        RoundRect.draw(fx, fy, fx + fw, fy + 20, 10, typing ? 0xFF2E2E36 : FIELD);
        if (typing) RoundRect.draw(fx - 1, fy - 1, fx + fw + 1, fy + 21, 11, 0x40FFFFFF);
        RoundRect.circle(fx + 13, fy + 9, 3.2f, SUB);
        RoundRect.circle(fx + 13, fy + 9, 2.1f, typing ? 0xFF2E2E36 : FIELD);
        Icons.line(fx + 15.2f, fy + 11.2f, fx + 17.5f, fy + 13.5f, 1.3f, SUB);
        String shown = query.isEmpty() && !typing ? "搜索歌曲、歌手、专辑" : query + (typing && System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "");
        Icons.text(Icons.fit(shown, fw - 34), fx + 24, fy + 6, query.isEmpty() && !typing ? DIM : TEXT);
        hit("searchbox", fx, fy, fw, 20, () -> typing = true, null);
        List<NeteaseTrack> l = tracks;
        if (loading && l == null) Icons.text("搜索中…", x + 8, y + 34, DIM);
        else if (l != null) rows(l, x, y + 32, w, h - 32, mx, my);
        else Icons.text("输入歌名、歌手或专辑，按回车", x + 8, y + 34, DIM);
    }

    /** The bottom: song, controls with the position, volume. */
    private void bar(int mx, int my) {
        float y = py + ph - BAR_H, x = px;
        RoundRect.draw(x + 5, y, x + pw - 5, y + BAR_H - 5, 8, BAR);
        NeteasePlayer p = player();
        NeteaseTrack t = p.current;
        float cx = px + pw / 2f, cyc = y + 15;
        if (t != null) {
            float tw = Math.min(140, cx - 66 - (x + 54));
            Covers.draw(module.covers, t.cover(96), x + 12, y + 8, 36, 255);
            Icons.text(Icons.fit(t.name, tw), x + 54, y + 15, TEXT);
            Icons.text(Icons.fit(p.state == NeteasePlayer.State.LOADING ? "加载中…" : t.artists, tw), x + 54, y + 27, SUB);
        }
        boolean shuffle = p.shuffle;
        NeteasePlayer.Repeat repeat = p.repeat;
        control("shuffle", cx - 52, cyc, mx, my, () -> p.shuffle = !p.shuffle, c -> Icons.shuffle(cx - 52, cyc, 9, shuffle ? RED : c));
        if (shuffle) RoundRect.circle(cx - 52, cyc + 8, 1, RED);
        control("prev", cx - 26, cyc, mx, my, p::previous, c -> Icons.skip(cx - 26, cyc, 9, false, c));
        boolean hoverPlay = over(mx, my, cx - 11, cyc - 11, 22, 22);
        RoundRect.circle(cx, cyc, hoverPlay ? 11.5f : 11, RED);
        if (p.state == NeteasePlayer.State.PLAYING) Icons.pause(cx, cyc, 9, TEXT);
        else Icons.play(cx + 0.5f, cyc, 9, TEXT);
        hit("play", cx - 11, cyc - 11, 22, 22, p::togglePause, null);
        control("next", cx + 26, cyc, mx, my, p::next, c -> Icons.skip(cx + 26, cyc, 9, true, c));
        control("repeat", cx + 52, cyc, mx, my, p::cycleRepeat,
            c -> Icons.repeat(cx + 52, cyc, 10, repeat == NeteasePlayer.Repeat.ONE, repeat == NeteasePlayer.Repeat.OFF ? c : RED));
        if (repeat != NeteasePlayer.Repeat.OFF) RoundRect.circle(cx + 52, cyc + 8, 1, RED);
        float bw = Math.min(220, pw - 300), bx = cx - bw / 2f, by = y + 36;
        long dur = t == null ? 0 : t.durationMs, pos = p.positionMs();
        float f = drag != null && drag.equals("progress") ? dragValue : dur <= 0 ? 0 : Math.min(1, pos / (float) dur);
        boolean hoverBar = drag != null && drag.equals("progress") || over(mx, my, bx, by - 4, bw, 10);
        String left = Icons.time(drag != null && drag.equals("progress") ? (long) (dragValue * dur) : pos), right = Icons.time(dur);
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.75f, 0.75f, 1f);
        Icons.text(left, (bx - 6 - Icons.width(left) * 0.75f) / 0.75f, (by - 2) / 0.75f, SUB);
        Icons.text(right, (bx + bw + 6) / 0.75f, (by - 2) / 0.75f, SUB);
        GlStateManager.popMatrix();
        RoundRect.draw(bx, by, bx + bw, by + 3, 1.5f, 0xFF4D4D4D);
        if (f > 0) RoundRect.draw(bx, by, bx + Math.max(3, bw * f), by + 3, 1.5f, hoverBar ? RED : TEXT);
        if (hoverBar) RoundRect.circle(bx + bw * f, by + 1.5f, 4, TEXT);
        hit("progress", bx, by - 4, bw, 11, null, null);
        int vol = drag != null && drag.equals("volume") ? Math.round(dragValue * 100) : module.volume.getInt();
        float vx = px + pw - 92, vwid = 70, vy = y + 26;
        Icons.speaker(vx - 10, vy + 1.5f, 9, vol, SUB);
        boolean hoverVol = drag != null && drag.equals("volume") || over(mx, my, vx, vy - 4, vwid, 11);
        RoundRect.draw(vx, vy, vx + vwid, vy + 3, 1.5f, 0xFF4D4D4D);
        if (vol > 0) RoundRect.draw(vx, vy, vx + Math.max(3, vwid * vol / 100f), vy + 3, 1.5f, hoverVol ? RED : TEXT);
        if (hoverVol) RoundRect.circle(vx + vwid * vol / 100f, vy + 1.5f, 4, TEXT);
        hit("volume", vx, vy - 4, vwid, 11, null, null);
        String q = module.quality.get();
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.75f, 0.75f, 1f);
        Icons.text(q + (p.message.equals("只能试听片段") ? " · 试听" : ""), (vx - 14) / 0.75f, (y + 10) / 0.75f, RED);
        GlStateManager.popMatrix();
    }

    private interface Paint {
        void draw(int color);
    }

    private void control(String id, float cx, float cy, int mx, int my, Runnable action, Paint paint) {
        boolean hover = over(mx, my, cx - 8, cy - 8, 16, 16);
        paint.draw(hover ? TEXT : SUB);
        hit(id, cx - 8, cy - 8, 16, 16, action, null);
    }

    // ------------------------------------------------------------------ input

    private Hit find(String id) {
        for (Hit h : hits) if (h.id.equals(id)) return h;
        return null;
    }

    @Override
    protected void mouseClicked(int gmx, int gmy, int button) { clickAt(gmx / scale, gmy / scale, button); }

    private void clickAt(float fx, float fy, int button) {
        int mx = (int) fx, my = (int) fy;
        Hit progress = find("progress"), volume = find("volume");
        if (button == 0 && progress != null && progress.over(mx, my)) {
            drag = "progress";
            dragValue = Math.max(0, Math.min(1, (mx - progress.x) / progress.w));
            return;
        }
        if (button == 0 && volume != null && volume.over(mx, my)) {
            drag = "volume";
            dragValue = Math.max(0, Math.min(1, (mx - volume.x) / volume.w));
            return;
        }
        if (section == Section.SEARCH) typing = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.over(mx, my)) continue;
            Runnable r = button == 0 ? h.left : button == 1 ? h.right : null;
            if (r != null) {
                r.run();
                return;
            }
        }
    }

    @Override
    protected void mouseClickMove(int gmx, int gmy, int button, long time) { dragAt(gmx / scale); }

    private void dragAt(float mx) {
        if (drag == null) return;
        Hit h = find(drag);
        if (h != null) dragValue = Math.max(0, Math.min(1, (mx - h.x) / h.w));
    }

    @Override
    protected void mouseReleased(int gmx, int gmy, int button) { release(); }

    private void release() {
        if (drag == null) return;
        NeteaseTrack t = player().current;
        if (drag.equals("progress") && t != null) player().seek((long) (dragValue * t.durationMs));
        if (drag.equals("volume")) module.volume.set(Math.round(dragValue * 100));
        drag = null;
    }

    /** Drags the position or volume bar to this fraction (as the mouse would: down, move, up). */
    public void dragTo(String bar, float fraction) {
        Hit h = find(bar);
        if (h == null) return;
        clickAt(h.x + 1, h.y + h.h / 2, 0);
        dragAt(h.x + h.w * fraction);
        release();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        float mx = Mouse.getEventX() * width / (float) mc.displayWidth / scale;
        float my = (height - Mouse.getEventY() * height / (float) mc.displayHeight - 1) / scale;
        if (mx < px + SIDE_W && my >= sideTop && my < sideBottom) sideScroll = Math.max(0, Math.min(sideMax, sideScroll - Integer.signum(wheel) * 26));
        else scroll = Math.max(0, Math.min(maxScroll, scroll - Integer.signum(wheel) * ROW * 2));
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (typing && section == Section.SEARCH) {
            if (key == Keyboard.KEY_ESCAPE) {
                typing = false;
                return;
            }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                typing = false;
                search(query);
            } else if (key == Keyboard.KEY_BACK) {
                if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            } else if (ChatAllowedCharacters.isAllowedCharacter(c) && query.length() < 80) {
                query += c;
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        NeteasePlayer p = player();
        int step = module.volumeStep.getInt();
        switch (key) {
            case Keyboard.KEY_SPACE: p.togglePause(); break;
            case Keyboard.KEY_RIGHT: p.seek(p.positionMs() + 10000); break;
            case Keyboard.KEY_LEFT: p.seek(Math.max(0, p.positionMs() - 10000)); break;
            case Keyboard.KEY_UP: module.changeVolume(step); break;
            case Keyboard.KEY_DOWN: module.changeVolume(-step); break;
            default:
                if (section == Section.SEARCH && ChatAllowedCharacters.isAllowedCharacter(c) && c != ' ') {
                    typing = true;
                    query += c;
                }
        }
    }

    /** Types into the search box (for the tests); enter: search. */
    public void type(String text, boolean enter) {
        open(Section.SEARCH);
        for (char c : text.toCharArray()) keyTyped(c, 0);
        if (enter) keyTyped('\r', Keyboard.KEY_RETURN);
    }
}
