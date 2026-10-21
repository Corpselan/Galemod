package gale.module.impl.client;

import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.util.TeamUtil;

/** Who counts as a target for every module (TargetFinder starts from these). */
public final class Targets extends Module {
    public final BoolSetting players = bool("Players", true);
    public final BoolSetting mobs = bool("Mobs", false);
    public final BoolSetting animals = bool("Animals", false);
    public final BoolSetting invisibles = bool("Invisibles", false);
    public final BoolSetting teams = bool("Skip teammates", true);
    public final ModeSetting teamMode = mode("Team check", "Any", "Any", "Scoreboard", "Name color", "Armor color")
        .visibleWhen(teams::get);
    public final BoolSetting bots = bool("Skip bots", true).describe("不在 Tab 列表里的、Hypixel NPC");
    public final BoolSetting friends = bool("Skip friends", true);
    public final BoolSetting walls = bool("Through walls", false);

    public Targets() {
        super("Targets", Category.CLIENT, "所有模块找目标时用的规则");
        alwaysOn();
    }

    /** The team check chosen, or null for any of them. */
    public TeamUtil.Check teamCheck() {
        switch (teamMode.get()) {
            case "Scoreboard": return TeamUtil.Check.SCOREBOARD;
            case "Name color": return TeamUtil.Check.NAME_COLOR;
            case "Armor color": return TeamUtil.Check.ARMOR_COLOR;
            default: return null;
        }
    }
}
