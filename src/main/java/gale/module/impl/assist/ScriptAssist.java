package gale.module.impl.assist;

import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.script.Assists;

/**
 * One area of the script API that B4 scripts use (packets, combat hooks, data...). On: scripts can use it. Off: its
 * functions give null with a note in chat, and its events stop. Some areas have a switch per part (System: files,
 * clipboard, browser, network). The settings screen shows which scripts use it.
 */
public final class ScriptAssist extends Module {
    private final String area;

    public ScriptAssist(String name, String area, String description) {
        super(name, Category.ASSIST, description);
        this.area = area;
        startOn();
        setHidden(true);
        Assists.bind(area, name, this::isEnabled);
    }

    /** A switch for one part of the area: part("Files", "files", "读写文件") controls "system.files". */
    public ScriptAssist part(String setting, String sub, String description) {
        BoolSetting s = bool(setting, true).describe(description);
        Assists.bind(area + "." + sub, getName() + " → " + setting, () -> isEnabled() && s.get());
        return this;
    }

    public String area() { return area; }

    @Override
    public String getInfo() { return isEnabled() ? Assists.status(area) : null; }
}
