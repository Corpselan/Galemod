package gale.b4port.module;

/** What each module ported from B4 does, for Gale's GUI. */
final class Descriptions {
    private Descriptions() { }

    static String of(String name) {
        switch (name) {
            case "KillAura": return "B4 的 KillAura：自动打范围内的目标，可以自动格挡、静默转头";
            case "AimAssist": return "B4 的 AimAssist：把准星慢慢拉向目标";
            case "Backtrack": return "B4 的 Backtrack：延迟目标的位置包，打它刚才的位置";
            case "Lag Range": return "B4 的 Lag Range：靠近目标前压住自己的移动包，再一次放出";
            case "BedAura": return "B4 的 BedAura：自动挖范围内的床";
            case "Scaffold": return "B4 的 Scaffold：脚下自动搭路";
            case "Tower": return "B4 的 Tower：配合 Scaffold 往上搭";
            case "Bed Wars": return "B4 的 Bed Wars：敌人拿出火球、珍珠、隐身药等时提醒，商店点击、存取资源";
            case "AntiCheat": return "B4 的 AntiCheat：发现别人开挂（Scaffold、AutoBlock、NoSlow、拆床……）时提醒";
            case "AntiBot": return "B4 的 AntiBot：从 B4 搬来的模块不打 Hypixel 的假人";
            case "Nametags": return "B4 的 Nametags：玩家头上换成自己的名牌（血量、距离、几刀打死、装备和附魔）";
            case "PlayerESP": return "B4 的 PlayerESP：给玩家描边、发光、画框或圈，隔墙也看得见";
            case "Chams": return "B4 的 Chams：隔着墙也画出玩家";
            case "B4 Settings": return "从 B4 搬来的模块共用的设置（B4 的 Settings）";
            default: return "";
        }
    }
}
