package gale;

import gale.b4script.ScriptDelegated;
import gale.b4script.ScriptEvents;
import gale.b4script.ScriptManagerModule;
import gale.b4script.ScriptReflection;
import gale.bedwars.BedTracker;
import gale.bridge.Bridge;
import gale.command.GaleCommand;
import gale.config.ConfigManager;
import gale.core.Hooks;
import gale.event.EventBus;
import gale.module.Module;
import gale.module.ModuleManager;
import gale.module.impl.client.BridgeModule;
import gale.module.impl.client.ClickGuiModule;
import gale.module.impl.client.ContactUs;
import gale.module.impl.client.HUD;
import gale.module.impl.client.Rotations;
import gale.module.impl.client.Targets;
import gale.module.impl.assist.ScriptAssist;
import gale.module.impl.optimize.Performance;
import gale.module.impl.optimize.Profiler;
import gale.module.impl.render.KillEffects;
import gale.module.impl.render.JettModel;
import gale.module.impl.render.LocalSpin;
import gale.module.impl.render.SwordModel;
import gale.module.impl.render.BowModel;
import gale.module.impl.render.Island;
import gale.module.impl.render.Trajectories;
import gale.module.impl.world.AutoDef;
import gale.script.api.ScriptApis;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Gale: a client mod that runs next to other clients (B4 and others) and fills in what they miss. No mixins: it hooks
 * the game through Forge events, its own handler in the network pipeline, and a wrapper around the movement input.
 */
@Mod(modid = Gale.ID, name = Gale.NAME, version = Gale.VERSION, clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public final class Gale {
    public static final String ID = "galemod", NAME = "GaleMod", VERSION = "2.0";
    public static final Logger LOG = LogManager.getLogger(NAME);
    /** Gale's event bus: modules listen here (see gale.event.impl for the events). */
    public static final EventBus BUS = new EventBus();

    private static final ModuleManager MODULES = new ModuleManager();
    private static final ConfigManager CONFIG = new ConfigManager();

    public static ModuleManager modules() { return MODULES; }

    public static ConfigManager config() { return CONFIG; }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        gale.security.ReleaseIntegrity.assertTrustedClass(Gale.class);
        Hooks.install();
        gale.ui.raven.MandatoryWatermarkOverlay.install();
        gale.security.RuntimeIntegrityGuard.install();
        // the HUD's layout: with the chat open, HUD elements can be moved and resized (galemod/hud.json)
        gale.hud.HudLayout.install();
        // the framework's own modules; feature modules get registered here as they are written
        // (gale.example has four small ones showing the APIs; they are for development and not in the jar)
        MODULES.register(new HUD(), new ClickGuiModule(), new ContactUs(), new Targets(), new Rotations(), new BridgeModule());
        MODULES.register(new Performance(), new Profiler());
        MODULES.register(new KillEffects());
        MODULES.register(new JettModel());
        MODULES.register(new LocalSpin());
        SwordModel swords=new SwordModel();MODULES.register(swords);swords.setEnabled(true);
        BowModel bows=new BowModel();MODULES.register(bows);bows.setEnabled(true);
        // Bed Wars: your team and bed for every module (BedTracker), the island HUD and the bed defender
        BedTracker.install();
        MODULES.register(new Island(), new AutoDef());
        MODULES.register(new Trajectories());
        // B4's modules, ported from OpenB4 (gale.b4port): each gives way to B4's own while B4 runs it
        gale.b4port.B4Port.install();
        // Pug's Jappylay stats overlay (a B4 script), and its /ov
        MODULES.register(new gale.module.impl.minigames.Jappylay());
        // Spotify: what is playing and its controls (gale.spotify), and /spotify
        MODULES.register(new gale.spotify.SpotifyModule());
        // NetEase Cloud Music, played by Gale itself (gale.netease), and /netease; the 3D lyrics follow either player (gale.lyrics)
        MODULES.register(new gale.netease.NeteaseModule(), new gale.lyrics.LyricsModule());
        // B4 scripts run by Gale itself (its own copy of B4's script API, from OpenB4); they load on the first world join
        ScriptReflection.init();
        if (!ScriptEvents.bind(ScriptDelegated.class)) LOG.warn("B4 script events could not be bound");
        MODULES.register(new ScriptManagerModule());
        // the script API's areas, each switchable in ClickGUI's Assist category
        MODULES.register(
            new ScriptAssist("Script Packets", "packets", "B4 脚本读、改、造、扣任何数据包，看到所有收发的包")
                .part("Hold", "hold", "扣包和放包（packet.hold / release / drop）"),
            new ScriptAssist("Script Combat", "combat", "攻击、击退、爆炸、实体状态的钩子，只在本地的攻击、挥手和格挡"),
            new ScriptAssist("Script Data", "data", "服务器位置、别人的速度、朝向、用物品时间、方块硬度和碰撞箱、任意起点射线、移动和抛射物预测"),
            new ScriptAssist("Script Render", "render", "隐藏或透视实体、名字、手、HUD 的部分、方块框，改视野和相机，游戏选项")
                .part("Particles", "particles", "接管粒子系统，让脚本能去掉粒子"),
            new ScriptAssist("Script Input", "input", "吞掉按键、改鼠标移动")
                .part("Keys", "keys", "按键事件和吞键")
                .part("Mouse", "mouse", "接管鼠标移动"),
            new ScriptAssist("Script Screens", "screens", "脚本自己的界面（按钮、输入框），能点的聊天消息，填聊天框"),
            new ScriptAssist("Script System", "system", "文件、剪贴板、打开网页、TCP 和 WebSocket")
                .part("Files", "files", "读写文件")
                .part("Clipboard", "clipboard", "读写剪贴板")
                .part("Browser", "browser", "用浏览器打开网页")
                .part("Network", "network", "TCP 和 WebSocket 连接"),
            new ScriptAssist("Script Crosshair", "pick", "改这一 tick 准星指着谁（Reach、Hitbox）"));
        // Meowtils extensions (.meowtils) from .minecraft/galemod/extensions (and Meowtils' folder when Meowtils is not installed)
        gale.meowtils.ExtensionLoader.loadAll();
        for (Module m : MODULES.all()) if (m.isAlwaysOn()) m.setEnabled(true);
        CONFIG.load();
        gale.meowtils.ExtensionLoader.started();
        Bridge.start();
        // what B4 scripts can call through bridge.get("gale") (see docs/SCRIPTS.md)
        ScriptApis.install();
        ClientCommandHandler.instance.registerCommand(new GaleCommand());
        ClientCommandHandler.instance.registerCommand(new gale.module.impl.minigames.OverlayCommand());
        ClientCommandHandler.instance.registerCommand(new gale.spotify.SpotifyCommand());
        ClientCommandHandler.instance.registerCommand(new gale.netease.NeteaseCommand());
        LOG.info("{} {} loaded with {} modules", NAME, VERSION, MODULES.all().size());
        // development only: gradlew runClient -Pdevtest runs the in-game self test (the test classes are not in the jar)
        if (Boolean.getBoolean("gale.devtest")) {
            try {
                Class.forName("gale.devtest.DevTest").getMethod("start").invoke(null);
            } catch (Throwable t) {
                LOG.error("Dev test could not start", t);
            }
        }
    }
}
