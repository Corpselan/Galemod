package gale.script;

import gale.Gale;
import gale.util.ChatUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Problems in script calls and callbacks: to chat (the same one at most every 5 seconds) and to the log. */
public final class ScriptErrors {
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private ScriptErrors() { }

    /** @param where e.g. "gale.attackLocal" or "LeaderCombat 的 attack 回调" */
    public static void report(String where, String message, Throwable t) {
        String text = message != null ? message : describe(t);
        String key = where + "|" + text;
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last != null && now - last < 5000) return;
        LAST.put(key, now);
        ChatUtil.error(where + "：" + text);
        if (t != null) Gale.LOG.warn("Script problem in {}", where, t);
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof java.lang.reflect.InvocationTargetException || c instanceof RuntimeException && c.getMessage() == null))
            c = c.getCause();
        String line = scriptLine(c);
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage()) + line;
    }

    /**
     * " (第 N 行)" when the error happened in a B4 script: B4 names script classes with I and l only, and its lines are
     * 20 off (B4's own wrapper code comes first), the same way B4 reports script errors.
     */
    private static String scriptLine(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            String cls = e.getClassName();
            int dollar = cls.indexOf('$');
            String outer = dollar < 0 ? cls : cls.substring(0, dollar);
            if (outer.length() >= 20 && outer.chars().allMatch(ch -> ch == 'I' || ch == 'l') && e.getLineNumber() > 20)
                return "（脚本第 " + (e.getLineNumber() - 20) + " 行，" + e.getMethodName() + "）";
        }
        return "";
    }
}
