package gale.module;

/** Where a module shows up in the GUI and lists. */
public enum Category {
    COMBAT("Combat", "战斗"),
    MOVEMENT("Movement", "移动"),
    PLAYER("Player", "玩家"),
    WORLD("World", "世界"),
    RENDER("Render", "渲染"),
    /** Bed Wars and other Hypixel games: alerts, spotting cheaters */
    MINIGAMES("Minigames", "小游戏"),
    /** frame rate: where frames go, and making them cheaper */
    OPTIMIZE("Optimize", "优化"),
    /** things that work together with another client */
    ASSIST("Assist", "辅助"),
    /** B4 scripts that Gale runs itself (gale.b4script) */
    SCRIPTS("Scripts", "脚本"),
    /** modules from Meowtils extensions (.meowtils files, gale.meowtils) */
    EXTENSIONS("Extensions", "扩展"),
    /** Gale's own settings: HUD, rotations, targets, bridge */
    CLIENT("Client", "客户端");

    public final String display, chinese;

    Category(String display, String chinese) {
        this.display = display;
        this.chinese = chinese;
    }
}
