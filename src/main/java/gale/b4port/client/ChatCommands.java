package gale.b4port.client;

import gale.b4port.module.SettingsModule;

/** B4's distance text ("&c12m"), as its alerts show it. */
public final class ChatCommands {
    private ChatCommands() {
    }

    public static String distance(double d, boolean brackets) {
        char c = d <= 10.0 ? 'c' : (d <= 25.0 ? '6' : (d <= 50.0 ? 'e' : (d <= 100.0 ? 'a' : '2')));
        String s = "&" + c + (int) d + "m";
        if (brackets && SettingsModule.addBracketsToDistance != null && SettingsModule.addBracketsToDistance.isToggled()) {
            s = "&7(" + s + "&7)";
        }
        return s;
    }
}
