package gale.spotify.ui;

import gale.spotify.SpotifyModule;
import gale.spotify.SpotifyPlayer;
import gale.spotify.model.Device;
import gale.spotify.model.Playback;
import gale.spotify.model.Playlist;
import gale.spotify.model.Track;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The player, in Spotify's colours: on the left what to show (now playing, liked songs, playlists, search, devices,
 * and your playlists by name), in the middle the list, at the bottom the controls, the position (drag to seek) and
 * the volume. Left click a song plays it, right click adds it to the queue. Space plays / pauses, arrows seek and
 * change the volume.
 */
public final class SpotifyScreen extends GuiScreen {
    public enum Section { NOW, LIKED, PLAYLISTS, PLAYLIST, SEARCH, DEVICES }

    static final int GREEN = 0xFF1DB954, BG = 0xF5121212, SIDE = 0xFF000000, BAR = 0xFF181818, TEXT = 0xFFFFFFFF, SUB = 0xFFB3B3B3,
        DIM = 0xFF6A6A6A, HOVER = 0x14FFFFFF, FIELD = 0xFF242424, WARN = 0xFFE8A33C;
    private static final float SIDE_W = 128, BAR_H = 56, ROW = 20;

    private final SpotifyModule module;
    private Section section;
    private volatile List<Track> tracks, upNext;
    private volatile List<Playlist> playlists;
    private volatile boolean loading;
    private Playlist playlist;
    private String query = "";
    private boolean typing;
    private float scroll, maxScroll, sideScroll, sideMax;
    // the screen has its own scale (like GUI scale 2, a bit smaller on small windows): this is how its units relate to
    // the GUI's, and its size in its own units
    private float scale = 1f, vw, vh;
    private final List<Hit> hits = new ArrayList<>();
    private String drag;
    private float dragValue;
    private String toast = "";
    private long toastAt;
    // the panel, as laid out this frame (the screen's own units)
    private float px, py, pw, ph, sideTop, sideBottom;

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

    public SpotifyScreen(SpotifyModule module) { this(module, Section.NOW); }

    public SpotifyScreen(SpotifyModule module, Section section) {
        this.module = module;
        this.section = section;
    }

    private SpotifyPlayer player() { return module.spotify(); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        player().fast = true;
        player().refreshSoon(0);
        if (playlists == null && player().api().loggedIn()) player().playlists(l -> playlists = l);
        open(section);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        player().fast = false;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------ what is shown (also for the tests)

    public Section section() { return section; }

    public List<Track> tracks() { return tracks; }

    public List<Playlist> playlists() { return playlists; }

    public String toast() { return toast; }

    public void open(Section s) {
        section = s;
        scroll = 0;
        typing = s == Section.SEARCH;
        if (!player().api().loggedIn()) return;
        switch (s) {
            case NOW:
                player().upNext(l -> upNext = l);
                break;
            case LIKED:
                load();
                player().liked(this::loaded);
                break;
            case PLAYLISTS:
                player().playlists(l -> playlists = l);
                break;
            case DEVICES:
                player().loadDevices(null);
                break;
            case SEARCH:
                if (!query.isEmpty()) search(query);
                else tracks = null;
                break;
            default:
                break;
        }
    }

    private void load() {
        tracks = null;
        loading = true;
    }

    private void loaded(List<Track> l) {
        tracks = l;
        loading = false;
    }

    public void openPlaylist(Playlist p) {
        playlist = p;
        section = Section.PLAYLIST;
        scroll = 0;
        typing = false;
        load();
        player().playlistTracks(p.id, this::loaded);
    }

    public void search(String q) {
        query = q;
        section = Section.SEARCH;
        scroll = 0;
        if (q.trim().isEmpty()) return;
        load();
        player().search(q.trim(), this::loaded);
    }

    private void say(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
    }

    /** Clicks what has this id (as the mouse would, at its middle); false if it is not on screen. */
    public boolean click(String id, int button) throws IOException {
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

    /** Clip to a rectangle in the screen's units. */
    private void clip(float x, float y, float w, float h) { RenderUtil.scissor(x * scale, y * scale, w * scale, h * scale); }

    private void measure() {
        int factor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
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
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1f);
        pw = Math.min(540, vw - 20);
        ph = Math.min(330, vh - 16);
        px = (vw - pw) / 2f;
        py = (vh - ph) / 2f;
        RoundRect.draw(px, py, px + pw, py + ph, 10, BG);
        sidebar(mx, my);
        float cx = px + SIDE_W + 10, cy = py + 10, cw = pw - SIDE_W - 20, ch = ph - BAR_H - 18;
        if (!player().api().loggedIn()) login(cx, cy, cw, ch, mx, my);
        else content(cx, cy, cw, ch, mx, my);
        bar(mx, my);
        String msg = player().message;
        if (!msg.isEmpty() && player().api().loggedIn()) banner(msg, WARN);
        else if (!toast.isEmpty() && System.currentTimeMillis() - toastAt < 2500) banner(toast, GREEN);
        GlStateManager.popMatrix();
    }

    private void banner(String s, int color) {
        String text = Icons.fit(s, pw - SIDE_W - 40);
        float w = Icons.width(text) + 16, x = px + SIDE_W + 10 + (pw - SIDE_W - 20 - w) / 2f, y = py + ph - BAR_H - 22;
        RoundRect.draw(x, y, x + w, y + 14, 7, color);
        Icons.text(text, x + 8, y + 3, 0xFF000000);
    }

    private void sidebar(int mx, int my) {
        float x = px + 5, y = py + 5, w = SIDE_W - 2, h = ph - BAR_H - 12;
        RoundRect.draw(x, y, x + w, y + h, 8, SIDE);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 9, y + 9, 0);
        GlStateManager.scale(1.4f, 1.4f, 1f);
        Icons.text("Spotify", 0, 0, GREEN);
        GlStateManager.popMatrix();
        // the other player
        float pillR = x + w - 6, pillW = gale.netease.ui.NeteaseScreen.pillWidth("网易云");
        gale.netease.ui.NeteaseScreen.sourcePill("网易云", 0xFFEC4141, pillR, y + 8, over(mx, my, pillR - pillW, y + 8, pillW, 11));
        hit("source:netease", pillR - pillW, y + 8, pillW, 11, () -> {
            gale.netease.NeteaseModule n = gale.netease.NeteaseModule.instance;
            if (n == null) return;
            if (!n.isEnabled()) n.setEnabled(true);
            mc.displayGuiScreen(new gale.netease.ui.NeteaseScreen(n));
        }, null);
        String who = player().user == null ? (player().api().loggedIn() ? "…" : "未登录") : player().user.name + (player().user.premium() ? "" : " · 免费");
        Icons.text(Icons.fit(who, w - 18), x + 9, y + 24, DIM);
        Object[][] nav = { { Section.NOW, "正在播放" }, { Section.LIKED, "喜欢的歌曲" }, { Section.PLAYLISTS, "歌单" }, { Section.SEARCH, "搜索" },
            { Section.DEVICES, "设备" } };
        float ny = y + 40;
        for (Object[] n : nav) {
            Section s = (Section) n[0];
            boolean on = section == s || s == Section.PLAYLISTS && section == Section.PLAYLIST, hover = over(mx, my, x, ny, w, 16);
            if (on) RoundRect.draw(x + 4, ny, x + w - 4, ny + 16, 4, 0x22FFFFFF);
            navIcon(s, x + 15, ny + 8, on || hover ? TEXT : SUB);
            Icons.text((String) n[1], x + 26, ny + 4, on || hover ? TEXT : SUB);
            hit("nav:" + s.name().toLowerCase(), x, ny, w, 16, () -> open(s), null);
            ny += 18;
        }
        RenderUtil.rect(x + 9, ny + 3, x + w - 9, ny + 3.5f, 0x22FFFFFF);
        ny += 8;
        List<Playlist> lists = playlists;
        sideTop = ny;
        sideBottom = y + h - 4;
        if (lists == null) return;
        sideMax = Math.max(0, lists.size() * 13 - (sideBottom - sideTop));
        sideScroll = Math.max(0, Math.min(sideScroll, sideMax));
        clip(x, sideTop, w, sideBottom - sideTop);
        for (int i = 0; i < lists.size(); i++) {
            Playlist p = lists.get(i);
            float ly = sideTop + i * 13 - sideScroll;
            if (ly + 13 < sideTop || ly > sideBottom) continue;
            boolean on = section == Section.PLAYLIST && playlist != null && p.id.equals(playlist.id);
            boolean hover = over(mx, my, x, ly, w, 13) && my >= sideTop && my < sideBottom;
            Icons.text(Icons.fit(p.name, w - 18), x + 9, ly + 2, on ? GREEN : hover ? TEXT : SUB);
            if (ly >= sideTop - 1 && ly + 13 <= sideBottom + 1) hit("side:playlist:" + i, x, ly, w, 13, () -> openPlaylist(p), null);
        }
        RenderUtil.endScissor();
    }

    private void navIcon(Section s, float cx, float cy, int c) {
        switch (s) {
            case NOW: Icons.note(cx, cy, 9, c); break;
            case LIKED: Icons.heart(cx, cy, 8, c); break;
            case PLAYLISTS:
                for (int i = 0; i < 3; i++) RoundRect.draw(cx - 4, cy - 3.5f + i * 3, cx + 4, cy - 2.5f + i * 3, 0.4f, c);
                break;
            case SEARCH:
                RoundRect.circle(cx - 1, cy - 1, 3.2f, c);
                RoundRect.circle(cx - 1, cy - 1, 2.1f, SIDE);
                Icons.line(cx + 1.2f, cy + 1.2f, cx + 3.8f, cy + 3.8f, 1.3f, c);
                break;
            default: Icons.speaker(cx, cy, 8, 60, c); break;
        }
    }

    private void login(float x, float y, float w, float h, int mx, int my) {
        float cx = x + w / 2f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, y + 30, 0);
        GlStateManager.scale(1.6f, 1.6f, 1f);
        Icons.text("登录 Spotify", -Icons.width("登录 Spotify") / 2f, 0, TEXT);
        GlStateManager.popMatrix();
        String[] lines = module.clientId.get().trim().isEmpty()
            ? new String[]{ "第一次用要先建一个 Spotify 应用（免费，两分钟）：", "1. 打开 developer.spotify.com/dashboard，Create app",
                "2. Redirect URI 填 " + module.redirectUri(), "3. 勾 Web API，保存，复制 Client ID",
                "4. 填进 Gale 的 Spotify 模块设置里的 Client ID", "然后点下面的登录。" }
            : new String[]{ "会打开浏览器，在 Spotify 的页面登录并同意，", "然后回到游戏就行。控制播放需要 Premium。" };
        float ly = y + 58;
        for (String l : lines) {
            Icons.text(l, cx - Icons.width(l) / 2f, ly, SUB);
            ly += 12;
        }
        boolean busy = module.loggingIn();
        String label = busy ? "在浏览器里登录…" : "登录";
        float bw = Icons.width(label) + 36, bx = cx - bw / 2f, by = ly + 10;
        boolean hover = over(mx, my, bx, by, bw, 20) && !busy;
        RoundRect.draw(bx, by, bx + bw, by + 20, 10, busy ? DIM : hover ? 0xFF1ED760 : GREEN);
        Icons.text(label, cx - Icons.width(label) / 2f, by + 6, 0xFF000000);
        if (!busy) hit("login", bx, by, bw, 20, module::login, null);
    }

    private void content(float x, float y, float w, float h, int mx, int my) {
        switch (section) {
            case NOW: now(x, y, w, h, mx, my); break;
            case LIKED: trackList("喜欢的歌曲", null, x, y, w, h, mx, my); break;
            case PLAYLIST: trackList(playlist == null ? "" : playlist.name, playlist, x, y, w, h, mx, my); break;
            case PLAYLISTS: playlistGrid(x, y, w, h, mx, my); break;
            case SEARCH: searchView(x, y, w, h, mx, my); break;
            case DEVICES: devices(x, y, w, h, mx, my); break;
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

    private void now(float x, float y, float w, float h, int mx, int my) {
        Playback pb = player().playback;
        if (pb == null || pb.track == null) {
            title("没有在放的歌", null, x + 4, y + 6);
            Icons.text("在电脑或手机的 Spotify 里放一首，或者从左边的歌单里选一首。", x + 4, y + 30, SUB);
            return;
        }
        Track t = pb.track;
        float size = Math.min(110, h - 20);
        Covers.draw(module.covers, t.coverLarge != null ? t.coverLarge : t.cover, x + 4, y + 6, size, 255);
        float tx = x + size + 16, tw = w - size - 20;
        GlStateManager.pushMatrix();
        GlStateManager.translate(tx, y + 10, 0);
        GlStateManager.scale(1.5f, 1.5f, 1f);
        Icons.text(Icons.fit(t.name, tw / 1.5f), 0, 0, TEXT);
        GlStateManager.popMatrix();
        Icons.text(Icons.fit(t.artists, tw), tx, y + 26, SUB);
        Icons.text(Icons.fit(t.album, tw), tx, y + 38, DIM);
        if (pb.device != null) Icons.text(Icons.fit("在 " + pb.device.name + " 上播放", tw), tx, y + 52, GREEN);
        List<Track> next = upNext;
        float ly = y + 70;
        Icons.text("接下来", tx, ly, TEXT);
        ly += 12;
        if (next == null) {
            Icons.text("…", tx, ly, DIM);
            return;
        }
        for (int i = 0; i < next.size() && ly + 16 <= y + h; i++) {
            Track n = next.get(i);
            Covers.draw(module.covers, n.cover, tx, ly, 14, 255);
            Icons.text(Icons.fit(n.name + "  ·  " + n.artists, tw - 20), tx + 19, ly + 3, over(mx, my, tx, ly, tw, 16) ? TEXT : SUB);
            ly += 17;
        }
    }

    private void trackList(String name, Playlist list, float x, float y, float w, float h, int mx, int my) {
        List<Track> l = tracks;
        title(Icons.fit(name, (w - 40) / 1.5f), l == null ? (loading ? "加载中…" : "") : l.size() + " 首", x + 4, y + 4);
        if (l != null && !l.isEmpty()) {
            float bx = x + w - 22, by = y + 4;
            boolean hover = over(mx, my, bx - 9, by, 22, 22);
            RoundRect.circle(bx + 2, by + 9, 9, hover ? 0xFF1ED760 : GREEN);
            Icons.play(bx + 2.5f, by + 9, 8, 0xFF000000);
            hit("playall", bx - 9, by, 22, 20, () -> play(list, l, 0), null);
        }
        rows(l, list, x, y + 32, w, h - 32, mx, my);
    }

    private void play(Playlist list, List<Track> l, int i) {
        if (list != null) player().playContext(list.uri, l.get(i).uri);
        else {
            List<String> uris = new ArrayList<>();
            for (Track t : l) uris.add(t.uri);
            player().playTracks(uris, i);
        }
    }

    /** Song rows: number (or bars for the one playing), cover, name and artist, album, length. */
    private void rows(List<Track> l, Playlist list, float x, float y, float w, float h, int mx, int my) {
        if (l == null) return;
        Icons.text("#", x + 8, y, DIM);
        Icons.text("标题", x + 46, y, DIM);
        Icons.text("专辑", x + w * 0.62f, y, DIM);
        Icons.text("时长", x + w - 8 - Icons.width("时长"), y, DIM);
        RenderUtil.rect(x + 4, y + 11, x + w - 4, y + 11.5f, 0x22FFFFFF);
        float top = y + 14, bottom = y + h;
        maxScroll = Math.max(0, l.size() * ROW - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        Playback pb = player().playback;
        String playing = pb == null || pb.track == null ? "" : pb.track.uri;
        clip(x, top, w, bottom - top);
        for (int i = 0; i < l.size(); i++) {
            float ry = top + i * ROW - scroll;
            if (ry + ROW < top || ry > bottom) continue;
            Track t = l.get(i);
            boolean hover = over(mx, my, x, ry, w, ROW) && my >= top && my < bottom, current = t.uri.equals(playing);
            if (hover) RoundRect.draw(x + 2, ry, x + w - 2, ry + ROW, 4, HOVER);
            if (current && pb.playing) {
                long now = System.currentTimeMillis();
                for (int b = 0; b < 3; b++) {
                    float bh = 2 + 4 * (float) Math.abs(Math.sin(now / (170.0 + b * 37) + b));
                    RoundRect.draw(x + 7 + b * 3, ry + 13 - bh, x + 8.5f + b * 3, ry + 13, 0.4f, GREEN);
                }
            } else {
                Icons.text(String.valueOf(i + 1), x + 8, ry + 6, current ? GREEN : SUB);
            }
            Covers.draw(module.covers, t.cover, x + 24, ry + 2, 16, 255);
            float nameW = w * 0.62f - 52;
            Icons.text(Icons.fit(t.name, nameW), x + 46, ry + 2, current ? GREEN : TEXT);
            Icons.text(Icons.fit(t.artists, nameW), x + 46, ry + 11, SUB);
            Icons.text(Icons.fit(t.album, w * 0.38f - 40), x + w * 0.62f, ry + 6, SUB);
            String d = Icons.time(t.durationMs);
            Icons.text(d, x + w - 8 - Icons.width(d), ry + 6, SUB);
            int index = i;
            if (ry >= top - 1 && ry + ROW <= bottom + 1) hit("row:" + i, x, ry, w, ROW, () -> play(list, l, index), () -> {
                player().queue(t.uri);
                say("已加入队列：" + t.name);
            });
        }
        RenderUtil.endScissor();
        if (l.isEmpty()) Icons.text("这里还没有歌", x + 8, top + 6, DIM);
    }

    private void playlistGrid(float x, float y, float w, float h, int mx, int my) {
        List<Playlist> l = playlists;
        title("歌单", l == null ? "加载中…" : l.size() + " 个", x + 4, y + 4);
        if (l == null) return;
        int cols = Math.max(1, (int) ((w - 4) / 96));
        float cw = (w - 4) / cols, top = y + 32, bottom = y + h;
        int rowsN = (l.size() + cols - 1) / cols;
        maxScroll = Math.max(0, rowsN * 112 - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        clip(x, top, w, bottom - top);
        for (int i = 0; i < l.size(); i++) {
            Playlist p = l.get(i);
            float cx = x + 2 + (i % cols) * cw, cy = top + (i / cols) * 112 - scroll;
            boolean hover = over(mx, my, cx, cy, cw - 6, 106) && my >= top && my < bottom;
            RoundRect.draw(cx, cy, cx + cw - 6, cy + 106, 6, hover ? 0xFF282828 : 0xFF181818);
            float size = cw - 18;
            Covers.draw(module.covers, p.cover, cx + 6, cy + 6, Math.min(size, 72), 255);
            Icons.text(Icons.fit(p.name, cw - 18), cx + 6, cy + 82, TEXT);
            Icons.text(Icons.fit((p.owner.isEmpty() ? "" : p.owner + " · ") + p.tracks + " 首", cw - 18), cx + 6, cy + 93, SUB);
            if (cy >= top - 1 && cy + 106 <= bottom + 1) hit("playlist:" + i, cx, cy, cw - 6, 106, () -> openPlaylist(p), null);
        }
        RenderUtil.endScissor();
    }

    private void searchView(float x, float y, float w, float h, int mx, int my) {
        float fx = x + 4, fy = y + 4, fw = Math.min(260, w - 8);
        RoundRect.draw(fx, fy, fx + fw, fy + 20, 10, typing ? 0xFF2A2A2A : FIELD);
        if (typing) RoundRect.draw(fx - 1, fy - 1, fx + fw + 1, fy + 21, 11, 0x40FFFFFF);
        RoundRect.circle(fx + 13, fy + 9, 3.2f, SUB);
        RoundRect.circle(fx + 13, fy + 9, 2.1f, typing ? 0xFF2A2A2A : FIELD);
        Icons.line(fx + 15.2f, fy + 11.2f, fx + 17.5f, fy + 13.5f, 1.3f, SUB);
        String shown = query.isEmpty() && !typing ? "想听什么？" : query + (typing && System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "");
        Icons.text(Icons.fit(shown, fw - 34), fx + 24, fy + 6, query.isEmpty() && !typing ? DIM : TEXT);
        hit("searchbox", fx, fy, fw, 20, () -> typing = true, null);
        List<Track> l = tracks;
        if (loading && l == null) Icons.text("搜索中…", x + 8, y + 34, DIM);
        else if (l != null) rows(l, null, x, y + 32, w, h - 32, mx, my);
        else Icons.text("输入歌名、歌手或专辑，按回车", x + 8, y + 34, DIM);
    }

    private void devices(float x, float y, float w, float h, int mx, int my) {
        List<Device> l = player().devices;
        title("设备", l.isEmpty() ? "打开 Spotify 的电脑或手机会出现在这里" : "点一个，在它上面播放", x + 4, y + 4);
        float ry = y + 34;
        for (int i = 0; i < l.size(); i++) {
            Device d = l.get(i);
            boolean hover = over(mx, my, x, ry, w, 24);
            RoundRect.draw(x + 2, ry, x + w - 2, ry + 24, 5, d.active ? 0x221DB954 : hover ? HOVER : 0x00000000);
            Icons.speaker(x + 14, ry + 12, 9, d.volume, d.active ? GREEN : SUB);
            Icons.text(Icons.fit(d.name, w * 0.5f), x + 28, ry + 4, d.active ? GREEN : TEXT);
            Icons.text(d.type + (d.volume >= 0 ? " · 音量 " + d.volume + "%" : ""), x + 28, ry + 14, SUB);
            if (d.active) {
                String a = "正在播放";
                Icons.text(a, x + w - 8 - Icons.width(a), ry + 8, GREEN);
            }
            hit("device:" + i, x, ry, w, 24, () -> {
                player().transfer(d.id);
                say("在 " + d.name + " 上播放");
            }, null);
            ry += 27;
        }
    }

    /** The bottom: song, controls with the position, volume. */
    private void bar(int mx, int my) {
        float y = py + ph - BAR_H, x = px;
        RoundRect.draw(x + 5, y, x + pw - 5, y + BAR_H - 5, 8, BAR);
        Playback pb = player().playback;
        Track t = pb == null ? null : pb.track;
        float cx = px + pw / 2f, cyc = y + 15;
        if (t != null) {
            float tw = Math.min(140, cx - 66 - (x + 54));
            Covers.draw(module.covers, t.cover, x + 12, y + 8, 36, 255);
            Icons.text(Icons.fit(t.name, tw), x + 54, y + 15, TEXT);
            Icons.text(Icons.fit(t.artists, tw), x + 54, y + 27, SUB);
        }
        boolean shuffle = pb != null && pb.shuffle;
        String repeat = pb == null ? "off" : pb.repeat;
        control("shuffle", cx - 52, cyc, mx, my, () -> player().toggleShuffle(), c -> Icons.shuffle(cx - 52, cyc, 9, shuffle ? GREEN : c));
        if (shuffle) RoundRect.circle(cx - 52, cyc + 8, 1, GREEN);
        control("prev", cx - 26, cyc, mx, my, () -> player().previous(), c -> Icons.skip(cx - 26, cyc, 9, false, c));
        boolean hoverPlay = over(mx, my, cx - 11, cyc - 11, 22, 22);
        RoundRect.circle(cx, cyc, hoverPlay ? 11.5f : 11, TEXT);
        if (pb != null && pb.playing) Icons.pause(cx, cyc, 9, 0xFF000000);
        else Icons.play(cx + 0.5f, cyc, 9, 0xFF000000);
        hit("play", cx - 11, cyc - 11, 22, 22, () -> player().togglePlay(), null);
        control("next", cx + 26, cyc, mx, my, () -> player().next(), c -> Icons.skip(cx + 26, cyc, 9, true, c));
        control("repeat", cx + 52, cyc, mx, my, () -> player().cycleRepeat(), c -> Icons.repeat(cx + 52, cyc, 10, repeat.equals("track"), repeat.equals("off") ? c : GREEN));
        if (!repeat.equals("off")) RoundRect.circle(cx + 52, cyc + 8, 1, GREEN);
        // position
        float bw = Math.min(220, pw - 300), bx = cx - bw / 2f, by = y + 36;
        long dur = t == null ? 0 : t.durationMs, pos = pb == null ? 0 : pb.progressNow();
        float f = drag != null && drag.equals("progress") ? dragValue : dur <= 0 ? 0 : pos / (float) dur;
        boolean hoverBar = drag != null && drag.equals("progress") || over(mx, my, bx, by - 4, bw, 10);
        String left = Icons.time(drag != null && drag.equals("progress") ? (long) (dragValue * dur) : pos), right = Icons.time(dur);
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.75f, 0.75f, 1f);
        Icons.text(left, (bx - 6 - Icons.width(left) * 0.75f) / 0.75f, (by - 2) / 0.75f, SUB);
        Icons.text(right, (bx + bw + 6) / 0.75f, (by - 2) / 0.75f, SUB);
        GlStateManager.popMatrix();
        RoundRect.draw(bx, by, bx + bw, by + 3, 1.5f, 0xFF4D4D4D);
        if (f > 0) RoundRect.draw(bx, by, bx + Math.max(3, bw * f), by + 3, 1.5f, hoverBar ? GREEN : TEXT);
        if (hoverBar) RoundRect.circle(bx + bw * f, by + 1.5f, 4, TEXT);
        hit("progress", bx, by - 4, bw, 11, null, null);
        // volume
        int vol = drag != null && drag.equals("volume") ? Math.round(dragValue * 100) : pb == null ? -1 : pb.volume();
        float vx = px + pw - 92, vw = 70, vy = y + 26;
        Icons.speaker(vx - 10, vy + 1.5f, 9, Math.max(0, vol), SUB);
        boolean hoverVol = drag != null && drag.equals("volume") || over(mx, my, vx, vy - 4, vw, 11);
        RoundRect.draw(vx, vy, vx + vw, vy + 3, 1.5f, 0xFF4D4D4D);
        if (vol > 0) RoundRect.draw(vx, vy, vx + Math.max(3, vw * vol / 100f), vy + 3, 1.5f, hoverVol ? GREEN : TEXT);
        if (hoverVol && vol >= 0) RoundRect.circle(vx + vw * vol / 100f, vy + 1.5f, 4, TEXT);
        hit("volume", vx, vy - 4, vw, 11, null, null);
        if (pb != null && pb.device != null) {
            GlStateManager.pushMatrix();
            GlStateManager.scale(0.75f, 0.75f, 1f);
            Icons.text(Icons.fit(pb.device.name, 90 / 0.75f), (vx - 14) / 0.75f, (y + 10) / 0.75f, GREEN);
            GlStateManager.popMatrix();
        }
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
    protected void mouseClicked(int gmx, int gmy, int button) throws IOException { clickAt(gmx / scale, gmy / scale, button); }

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
        boolean wasTyping = typing;
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
        if (wasTyping && section == Section.SEARCH && !query.isEmpty()) typing = false;
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
        Playback pb = player().playback;
        if (drag.equals("progress") && pb != null && pb.track != null) player().seek((long) (dragValue * pb.track.durationMs));
        if (drag.equals("volume")) player().setVolume(Math.round(dragValue * 100));
        drag = null;
    }

    /** Drags the position or volume bar to this fraction (as the mouse would: down, move, up). */
    public void dragTo(String bar, float fraction) {
        Hit h = find(bar);
        if (h == null) return;
        float x = h.x + h.w * fraction, y = h.y + h.h / 2;
        clickAt(h.x + 1, y, 0);
        dragAt(x);
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
    protected void keyTyped(char c, int key) throws IOException {
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
        Playback pb = player().playback;
        int step = (int) module.volumeStep.get();
        switch (key) {
            case Keyboard.KEY_SPACE: player().togglePlay(); break;
            case Keyboard.KEY_RIGHT: if (pb != null) player().seek(pb.progressNow() + 10000); break;
            case Keyboard.KEY_LEFT: if (pb != null) player().seek(Math.max(0, pb.progressNow() - 10000)); break;
            case Keyboard.KEY_UP: player().changeVolume(step); break;
            case Keyboard.KEY_DOWN: player().changeVolume(-step); break;
            default:
                if (section == Section.SEARCH && ChatAllowedCharacters.isAllowedCharacter(c) && c != ' ') {
                    typing = true;
                    query += c;
                }
        }
    }

    /** Types into the search box (for the tests); enter: search. */
    public void type(String text, boolean enter) throws IOException {
        open(Section.SEARCH);
        for (char c : text.toCharArray()) keyTyped(c, 0);
        if (enter) keyTyped('\r', Keyboard.KEY_RETURN);
    }

}
