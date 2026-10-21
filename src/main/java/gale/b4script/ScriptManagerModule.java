package gale.b4script;

import gale.event.Priority;
import gale.event.impl.TickEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.TextSetting;
import gale.util.ChatUtil;

import java.awt.Desktop;

/**
 * B4's script "Manager" for the scripts Gale runs: where they are, loading them again, opening the folder. Scripts load
 * by themselves the first time you are in a world (as in B4); "Load scripts" reads the folder again.
 */
public final class ScriptManagerModule extends Module {
    public final TextSetting folder = text("Folder", "galemod/scripts").describe("放 B4 脚本的文件夹（相对 .minecraft）；填 keystrokes/scripts 就直接用 B4 的");
    private final BoolSetting autoLoad = bool("Auto load", true).describe("第一次进世界时自己加载（和 B4 一样）");
    private final BoolSetting loadScripts = bool("Load scripts", false).describe("重新读文件夹：新的和改过的编译加载，没改的继续跑");
    private final BoolSetting openFolder = bool("Open folder", false).describe("打开脚本文件夹");
    private boolean loadedOnce;

    public ScriptManagerModule() {
        super("Script Manager", Category.SCRIPTS, "在 Gale 里直接运行 B4 脚本：同样的 API、事件和设置（来自 OpenB4）");
        alwaysOn();
        loadScripts.onChange(v -> {
            if (!v) return;
            loadScripts.set(false);
            if (ScriptManager.reload()) loadedOnce = true;
        });
        openFolder.onChange(v -> {
            if (!v) return;
            openFolder.set(false);
            try {
                Desktop.getDesktop().open(ScriptManager.folder());
            } catch (Exception e) {
                ChatUtil.print("&7Scripts folder: &f" + ScriptManager.folder().getAbsolutePath());
            }
        });
        folder.onChange(v -> {
            if (!loadedOnce) return;
            ScriptManager.unloadAll();
            ScriptManager.reload();
        });
        onAlways(TickEvent.Pre.class, Priority.NORMAL, e -> {
            if (loadedOnce || !autoLoad.get() || mc.thePlayer == null || mc.theWorld == null) return;
            loadedOnce = true;
            ScriptManager.reload();
        });
    }

    @Override
    public String getInfo() {
        if (ScriptManager.compiler == null) return "没有 JDK";
        if (ScriptManager.loading) return "加载中";
        int on = 0;
        for (ScriptModule m : ScriptManager.modules()) if (m.isEnabled()) on++;
        return ScriptManager.modules().length + " 个脚本，" + on + " 个开着";
    }
}
