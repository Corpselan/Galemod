package gale.security;

import gale.util.GuiVisibility;
import java.util.Properties;

/** Author-controlled branding. Official changes must be rebuilt and signed with the release key. */
public final class WatermarkIdentity {
    /** Display name follows the same build edition as the GUI visibility rules. */
    public static final String USER = GuiVisibility.LEVEL == 1 ? "Dev"
        : GuiVisibility.LEVEL == 2 ? "Beta" : "user";
    public static final String EDITION = "galemod premium-beta";
    public static final String DEVELOPER = "Corpselan";
    public static final String WELCOME = "welcome, ";
    public static final String DEVELOPER_PREFIX = "dev. ";

    private WatermarkIdentity() { }

    public static Properties properties() {
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("user", USER);
        p.setProperty("level", String.valueOf(GuiVisibility.LEVEL));
        p.setProperty("edition", EDITION);
        p.setProperty("developer", DEVELOPER);
        p.setProperty("welcome", WELCOME);
        p.setProperty("developerPrefix", DEVELOPER_PREFIX);
        return p;
    }
}
