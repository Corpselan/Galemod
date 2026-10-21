package gale.b4port.module;

import gale.event.Priority;

/**
 * B4's order of modules in each hook (OpenB4's Hooks), as Gale listener priorities. B4 scripts running in Gale come
 * after the modules where B4 runs them after (gale.b4script.ScriptEvents: update and motion LOW, post motion
 * HIGHEST+100, input LOWEST, sent packets LOW, received packets HIGH).
 */
final class Order {
    private Order() { }

    private static int index(String name, String... order) {
        for (int i = 0; i < order.length; i++) if (order[i].equals(name)) return i;
        return order.length;
    }

    /** onPreUpdate: B4's two lists, then the scripts */
    static int preUpdate(String name) {
        return Priority.NORMAL + 60 - index(name, "Displace", "KillAura", "Scaffold", "SafeWalk", "NoSlow", "BedAura", "AutoRod", "Long Jump",
            "InvManager", "WoolWars", "AutoClicker", "AutoRequeue", "AutoWho", "Lag Range", "Limbo", "AutoLadder", "Water Bucket", "WTap", "Stasis", "Sprint");
    }

    /** getRotations: the first module in B4's list that answers wins (scripts are 40, AutoDef 50) */
    static int rotation(String name) {
        return 100 - index(name, "Freecam", "AutoBlockin", "Scaffold", "Long Jump", "AutoLadder", "Water Bucket", "Displace", "AimAssist", "NoSlow",
            "BedAura", "KillAura", "AutoRod", "AntiFireball", "WoolWars", "InvMove", "45 Jump");
    }

    static boolean rotatesWhenOff(String name) {
        return name.equals("Scaffold") || name.equals("KillAura") || name.equals("Freecam") || name.equals("Long Jump")
            || name.equals("AutoLadder") || name.equals("Displace") || name.equals("NoSlow");
    }

    /** onPreMotion: after Gale's own bookkeeping (PlayerUtils), before the scripts */
    static int preMotion(String name) {
        return Priority.NORMAL + 60 - index(name, "WTap", "BedAura", "Tower", "Scaffold", "NoSlow", "InvMove", "Bhop", "Long Jump", "Speed",
            "AutoPlace", "Freecam", "NoFall", "Speed Builders", "Bed Wars", "AntiCheat", "Duels Stats");
    }

    /** onPostMotion: after MotionHook put sprint / sneak back (HIGHEST+300), before the scripts (HIGHEST+100) */
    static int postMotion(String name) {
        return Priority.HIGHEST + 220 - index(name, "Displace", "KillAura", "NoSlow", "InvMove", "AutoBlock");
    }

    /** onPostPlayerInput: before the scripts */
    static int postInput(String name) {
        return Priority.NORMAL + 60 - index(name, "Scaffold", "Tower", "Freecam", "KillAura", "Bhop", "NoSlow", "SafeWalk", "Long Jump", "Dolphin",
            "AntiAFK", "InvMove", "TargetStrafe", "Stasis");
    }

    /** onSendPacket: after the scripts (B4 asks them first), Lag Range last */
    static int send(String name) {
        if (name.equals("Lag Range")) return Priority.LOW - 60;
        return Priority.LOW - 10 - index(name, "Chat Bypass", "Freecam", "KillAura", "AutoPlace", "BedAura", "AutoRod", "Hit Select", "NoSlow",
            "FastPlace", "AntiKnockback", "InvMove", "WTap", "AutoBlock");
    }

    /** onReceivePacket: after the scripts (B4 asks them first) */
    static int receive(String name) {
        // Backtrack holds packets back: after every other Gale listener saw them (they reach the game later, past Gale)
        if (name.equals("Backtrack")) return Priority.LOWEST - 10;
        return Priority.HIGH - 10 - index(name, "AntiCheat", "Freeze", "AntiKnockback", "Backtrack", "BedAura", "Hit Select", "Bed Wars", "Long Jump",
            "Jump Reset", "KillAura");
    }
}
