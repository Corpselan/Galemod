package gale.b4port.module.client;

import gale.bridge.B4State;
import gale.bridge.External;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.script.B4Link;

/**
 * Hypixel's /locraw, as B4 does it: half a second after each world join on Hypixel Gale asks "/locraw", hides the
 * answer and fires {@link gale.event.impl.LocrawEvent} (B4 scripts get onLocRaw). With B4 running, B4 asks itself and
 * GaleLink passes B4's answer on, so Gale does not ask a second time.
 */
public final class LocrawModule extends Module {
    private static LocrawModule instance;
    private final BoolSetting hide = bool("Hide answer", true).describe("不在聊天栏显示 /locraw 的回答");

    public LocrawModule() {
        super("Locraw", Category.CLIENT, "进 Hypixel 的每个世界时自动 /locraw，知道自己在哪个模式（B4 在时由 B4 问）");
        startOn();
        instance = this;
    }

    /** Whether Gale itself should ask: on, and no B4 here doing it. */
    public static boolean asks() {
        return instance != null && instance.isEnabled() && !b4Here();
    }

    public static boolean hideAnswer() {
        return instance == null || instance.hide.get();
    }

    static boolean b4Here() {
        return B4State.fresh() || External.clients().contains("b4") || B4Link.isLinked();
    }

    @Override
    public String getInfo() {
        return b4Here() ? "B4 在问" : null;
    }
}
