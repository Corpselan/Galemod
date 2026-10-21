package gale.spotify;

import gale.Gale;
import gale.core.Notifications;
import gale.core.Scheduler;
import gale.event.Priority;
import gale.event.impl.KeyEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.TickEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.TextSetting;
import gale.spotify.model.Playback;
import gale.spotify.model.SpotifyUser;
import gale.spotify.ui.SpotifyHud;
import gale.spotify.ui.SpotifyScreen;
import gale.util.ChatUtil;
import gale.util.KeyUtil;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/**
 * Spotify in the game: what is playing on a card on screen, and the controls (keys, /spotify, the player screen). The
 * sound stays in Spotify (on this computer, a phone, a speaker): Spotify lets other programs see and control its
 * playback, not play its songs themselves. Seeing works with any account; controlling needs Premium.
 */
public final class SpotifyModule extends Module {
    public static SpotifyModule instance;

    public final TextSetting clientId = text("Client ID", "").describe("你在 Spotify 开发者后台建的应用的 Client ID（/spotify help 有步骤）");
    public final NumberSetting port = number("Login port", SpotifyEndpoints.DEFAULT_PORT, 1024, 65535, 1)
        .describe("登录回调的端口，要和开发者后台的 Redirect URI 一致：http://127.0.0.1:端口/callback");
    public final BoolSetting hud = bool("HUD", true).describe("屏幕上显示正在放的歌（打开聊天框可以拖动和缩放）");
    public final BoolSetting hideWhenPaused = bool("Hide when paused", false).describe("暂停时不显示卡片");
    public final BoolSetting songToast = bool("Song notifications", true).describe("换歌时弹通知");
    public final NumberSetting refresh = number("Refresh", 2, 1, 10, 1).suffix("s").describe("多久问一次 Spotify 在放什么（打开播放器时每秒一次）");
    public final TextSetting playerKey = text("Player key", "NONE").describe("打开播放器的按键（键名，如 P、F8、MOUSE4；NONE 不绑）");
    public final TextSetting playKey = text("Play/Pause key", "NONE"), nextKey = text("Next key", "NONE"), prevKey = text("Previous key", "NONE");
    public final TextSetting volUpKey = text("Volume up key", "NONE"), volDownKey = text("Volume down key", "NONE");
    public final NumberSetting volumeStep = number("Volume step", 10, 1, 50, 1).suffix("%");

    public final CoverCache covers = new CoverCache();
    /** where the card is (moved and resized with the chat open) */
    public final gale.hud.HudBox box;
    /** opens the login page; the tests follow it themselves */
    public static volatile SpotifyAuth.Browser browser = SpotifyModule::openBrowser;
    private SpotifyApi api;
    private SpotifyPlayer player;
    private String apiClientId;
    private int apiPort;
    private volatile boolean loggingIn;
    private String lastError = "";
    private long lastErrorAt;

    public SpotifyModule() {
        super("Spotify", Category.CLIENT, "Spotify：显示正在放的歌，游戏里暂停、切歌、调音量、放歌单（/spotify）");
        instance = this;
        hideWhenPaused.visibleWhen(hud::get);
        box = gale.hud.HudLayout.box("spotify", "Spotify", this, gale.hud.HudBox.LEFT, gale.hud.HudBox.TOP, 4, 28, SpotifyHud.W, SpotifyHud.H);
        box.showWhen(hud::get);
        on(Render2DEvent.class, e -> SpotifyHud.draw(this, e));
        on(KeyEvent.class, this::key);
        onAlways(TickEvent.Post.class, Priority.NORMAL, e -> {
            SpotifyPlayer p = player;
            if (p == null) return;
            p.intervalMs = (int) (refresh.get() * 1000);
            p.fast = mc.currentScreen instanceof SpotifyScreen;
        });
    }

    /** The player for the Client ID and port set now (made again when they change; the login stays). */
    public synchronized SpotifyPlayer spotify() {
        String id = clientId.get().trim();
        int p = port.getInt();
        if (player == null || !id.equals(apiClientId) || p != apiPort) {
            boolean was = player != null && player.running();
            if (player != null) player.stop();
            api = new SpotifyApi(new SpotifyAuth(id, p), new TokenStore(new File(Gale.config().dir(), "spotify.json")));
            player = new SpotifyPlayer(api);
            player.onTrack = t -> Scheduler.main(() -> {
                if (songToast.get() && isEnabled()) Notifications.show("Spotify", t.name + (t.artists.isEmpty() ? "" : " - " + t.artists), Notifications.Kind.INFO, 3000);
            });
            player.onError = m -> Scheduler.main(() -> {
                // the same message is said once a minute at most (a refresh that keeps failing)
                if (m.equals(lastError) && System.currentTimeMillis() - lastErrorAt < 60000) return;
                lastError = m;
                lastErrorAt = System.currentTimeMillis();
                if (isEnabled()) ChatUtil.warn("Spotify: " + m);
            });
            apiClientId = id;
            apiPort = p;
            if (was || isEnabled()) player.start();
        }
        return player;
    }

    public String redirectUri() { return SpotifyEndpoints.redirect(port.getInt()); }

    public boolean loggingIn() { return loggingIn; }

    @Override
    protected void onEnable() {
        SpotifyPlayer p = spotify();
        p.start();
        if (!p.api().loggedIn() && mc.thePlayer != null) ChatUtil.info("Spotify：输入 /spotify login 登录" + (clientId.get().trim().isEmpty() ? "（先看 /spotify help 填 Client ID）" : ""));
    }

    @Override
    protected void onDisable() {
        if (player != null) player.stop();
    }

    @Override
    public String getInfo() {
        SpotifyPlayer p = player;
        if (p == null || !p.api().loggedIn()) return clientId.get().trim().isEmpty() ? "没填 Client ID" : "未登录";
        SpotifyUser u = p.user;
        Playback pb = p.playback;
        String who = u == null ? "已登录" : u.name + (u.premium() ? "" : "（免费：只能看）");
        return pb == null || pb.track == null ? who : who + " · " + (pb.playing ? "▶ " : "❚❚ ") + pb.track.name;
    }

    // ------------------------------------------------------------------ login

    /** Opens Spotify's login in the browser and waits for it (in the background, up to five minutes). */
    public void login() {
        if (loggingIn) {
            ChatUtil.info("已经在登录了：看浏览器");
            return;
        }
        if (clientId.get().trim().isEmpty()) {
            ChatUtil.error("先在 Spotify 模块设置里填 Client ID（/spotify help 有步骤）");
            return;
        }
        SpotifyPlayer p = spotify();
        loggingIn = true;
        Thread t = new Thread(() -> {
            try {
                TokenStore.Tokens tokens = p.api().auth().login(browser, 300_000);
                p.api().loggedIn(tokens);
                if (!p.running() && isEnabled()) p.start();
                p.loggedIn();
                Scheduler.main(() -> ChatUtil.print("&aSpotify 已登录"));
            } catch (SpotifyException e) {
                Scheduler.main(() -> ChatUtil.error("Spotify 登录没有完成：" + e.explain()));
            } catch (Exception e) {
                String why = e instanceof java.net.BindException ? "端口 " + port.getInt() + " 被占用了，换一个 Login port（开发者后台的 Redirect URI 也要改）" : e.toString();
                Scheduler.main(() -> ChatUtil.error("Spotify 登录没有完成：" + why));
            } finally {
                loggingIn = false;
            }
        }, "Gale Spotify login");
        t.setDaemon(true);
        t.start();
    }

    public void logout() {
        SpotifyPlayer p = spotify();
        p.api().logout();
        p.loggedOut();
        ChatUtil.print("&7Spotify 已退出登录（这台电脑上的登录信息已删除）");
    }

    private static void openBrowser(String url) {
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                opened = true;
            }
        } catch (Exception ignored) {
        }
        boolean finalOpened = opened;
        Scheduler.main(() -> {
            ChatComponentText link = new ChatComponentText(ChatUtil.color(finalOpened ? "&7浏览器打开了 Spotify 的登录页；没看到的话&b点这里" : "&7打不开浏览器：&b点这里打开登录页"));
            link.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(link);
        });
    }

    // ------------------------------------------------------------------ keys

    private void key(KeyEvent e) {
        if (!e.pressed || e.key == 0) return;
        SpotifyPlayer p = spotify();
        if (is(e.key, playerKey)) mc.displayGuiScreen(new SpotifyScreen(this));
        else if (is(e.key, playKey)) p.togglePlay();
        else if (is(e.key, nextKey)) p.next();
        else if (is(e.key, prevKey)) p.previous();
        else if (is(e.key, volUpKey)) p.changeVolume(volumeStep.getInt());
        else if (is(e.key, volDownKey)) p.changeVolume(-volumeStep.getInt());
    }

    private static boolean is(int key, TextSetting s) {
        int code = KeyUtil.code(s.get().trim());
        return code != 0 && code != -1 && code == key;
    }
}
