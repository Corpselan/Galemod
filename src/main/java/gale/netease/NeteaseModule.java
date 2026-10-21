package gale.netease;

import gale.Gale;
import gale.core.Notifications;
import gale.core.Scheduler;
import gale.event.Priority;
import gale.event.impl.KeyEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.TickEvent;
import gale.hud.HudBox;
import gale.hud.HudLayout;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.TextSetting;
import gale.netease.ui.NeteaseHud;
import gale.netease.ui.NeteaseScreen;
import gale.spotify.CoverCache;
import gale.util.KeyUtil;

import java.io.File;

/**
 * NetEase Cloud Music in the game: search, your playlists, recommended lists and charts, played by Gale itself (the
 * sound comes out of the game), with a card on screen and the 3D lyrics following along. Log in with the app's QR code
 * for your own playlists and VIP songs (/netease).
 */
public final class NeteaseModule extends Module {
    public static NeteaseModule instance;

    public final NumberSetting volume = number("Volume", 50, 0, 100, 1).suffix("%").describe("网易云音乐的音量");
    public final ModeSetting quality = mode("Quality", "192k", "128k", "192k", "320k").describe("音质：320k 大多要 VIP");
    public final BoolSetting hud = bool("HUD", true).describe("屏幕上显示正在放的歌（打开聊天框可以拖动和缩放）");
    public final BoolSetting hideWhenPaused = bool("Hide when paused", false).describe("暂停时不显示卡片");
    public final BoolSetting songToast = bool("Song notifications", true).describe("换歌时弹通知");
    public final TextSetting playerKey = text("Player key", "NONE").describe("打开播放器的按键（键名，如 O、F9、MOUSE5；NONE 不绑）");
    public final TextSetting playKey = text("Play/Pause key", "NONE"), nextKey = text("Next key", "NONE"), prevKey = text("Previous key", "NONE");
    public final TextSetting volUpKey = text("Volume up key", "NONE"), volDownKey = text("Volume down key", "NONE");
    public final NumberSetting volumeStep = number("Volume step", 10, 1, 50, 1).suffix("%");

    public final CoverCache covers = new CoverCache();
    public final HudBox box;

    public NeteaseModule() {
        super("NetEase Music", Category.CLIENT, "网易云音乐：搜歌、歌单、排行榜，在游戏里直接放（声音从游戏出来），扫码登录听自己的歌单和 VIP（/netease）");
        instance = this;
        NeteaseApi.cookieFile = new File(Gale.config().dir(), "netease.json");
        hideWhenPaused.visibleWhen(hud::get);
        box = HudLayout.box("netease", "网易云音乐", this, HudBox.LEFT, HudBox.TOP, 4, 74, NeteaseHud.W, NeteaseHud.H);
        box.showWhen(hud::get);
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        NeteasePlayer.log = s -> Gale.LOG.info("[NetEase] {}", s);
        p.onTrack = t -> Scheduler.main(() -> {
            if (songToast.get() && isEnabled()) Notifications.show("网易云音乐", t.name + (t.artists.isEmpty() ? "" : " - " + t.artists), Notifications.Kind.INFO, 3000);
        });
        on(Render2DEvent.class, e -> NeteaseHud.draw(this, e));
        on(KeyEvent.class, this::key);
        onAlways(TickEvent.Post.class, Priority.NORMAL, e -> {
            p.volume = (float) (volume.get() / 100.0);
            p.bitrate = quality.is("128k") ? 128000 : quality.is("320k") ? 320000 : 192000;
        });
    }

    public NeteasePlayer music() { return NeteasePlayer.INSTANCE; }

    @Override
    protected void onDisable() { NeteasePlayer.INSTANCE.stop(); }

    @Override
    public String getInfo() {
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        String who = NeteaseApi.loggedIn() ? (NeteaseApi.nickname.isEmpty() ? "已登录" : NeteaseApi.nickname) : "未登录";
        if (p.current == null) return who;
        return who + " · " + (p.state == NeteasePlayer.State.PLAYING ? "▶ " : "❚❚ ") + p.current.name;
    }

    public void changeVolume(int by) { volume.set(Math.max(0, Math.min(100, volume.get() + by))); }

    private void key(KeyEvent e) {
        if (!e.pressed || e.key == 0) return;
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        if (is(e.key, playerKey)) mc.displayGuiScreen(new NeteaseScreen(this));
        else if (is(e.key, playKey)) p.togglePause();
        else if (is(e.key, nextKey)) p.next();
        else if (is(e.key, prevKey)) p.previous();
        else if (is(e.key, volUpKey)) changeVolume(volumeStep.getInt());
        else if (is(e.key, volDownKey)) changeVolume(-volumeStep.getInt());
    }

    private static boolean is(int key, TextSetting s) {
        int code = KeyUtil.code(s.get().trim());
        return code != 0 && code != -1 && code == key;
    }
}
