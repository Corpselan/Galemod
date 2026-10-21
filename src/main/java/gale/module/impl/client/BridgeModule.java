package gale.module.impl.client;

import gale.bridge.Bridge;
import gale.bridge.External;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;

/** Settings of the bridge to other clients (see gale.bridge.Bridge and the GaleLink script for B4). */
public final class BridgeModule extends Module {
    public final BoolSetting http = bool("HTTP", true).describe("在 127.0.0.1 上开接口，让 B4 脚本读 Gale 的状态");
    public final NumberSetting port = number("Port", 25595, 1024, 65535, 1);
    public final NumberSetting stale = number("Report timeout", 3000, 500, 20000, 100).suffix("ms").describe("别的端多久没报告就当它关了");

    public BridgeModule() {
        super("Bridge", Category.CLIENT, "和别的端（B4 等）互通");
        alwaysOn();
        http.onChange(v -> Bridge.start());
        port.onChange(v -> Bridge.start());
        stale.onChange(v -> External.STALE_MILLIS = (long) stale.get());
    }
}
