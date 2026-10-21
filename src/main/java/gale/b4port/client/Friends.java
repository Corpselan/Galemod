package gale.b4port.client;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** B4's friends are Gale's friends (/gale friend); enemies are kept here, shared with Gale's B4 script runtime. */
public final class Friends {
    public static final Set<String> enemies = ConcurrentHashMap.newKeySet();

    private Friends() {
    }

    public static boolean isFriend(String s) {
        return gale.util.Friends.is(s);
    }

    public static boolean isEnemy(String s) {
        return s != null && enemies.contains(s.toLowerCase(Locale.ROOT));
    }

    public static boolean addEnemy(String s) {
        if (s != null && enemies.add(s.toLowerCase(Locale.ROOT))) {
            Chat.print("&7Added enemy: &b" + s);
            return true;
        }
        return false;
    }

    public static boolean removeEnemy(String s) {
        if (s != null && enemies.remove(s.toLowerCase(Locale.ROOT))) {
            Chat.print("&7Removed enemy: &b" + s);
            return true;
        }
        return false;
    }
}
