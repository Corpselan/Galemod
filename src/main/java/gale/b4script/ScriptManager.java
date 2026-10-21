package gale.b4script;

import com.google.gson.JsonObject;
import gale.Gale;
import gale.core.Scheduler;
import gale.module.Module;
import gale.module.setting.Setting;
import gale.util.ChatUtil;
import net.minecraftforge.common.MinecraftForge;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads B4 scripts from a folder and runs them as Gale modules (category Scripts), the way B4 does: same file and name
 * rules, same safety check, same imports and compiler, same events. Each script's settings and on/off state are saved
 * in Gale's config under its name.
 * <p>Differences from B4: javac runs on a background thread (no freeze while 40 scripts compile); a script whose file
 * did not change is left running on reload; the folder is set in the Script Manager module.
 */
public final class ScriptManager {
    public static final String EXTENSION = ".java";
    public static final File tempDir = new File(System.getProperty("java.io.tmpdir"), "gale-b4scripts");
    public static final String BASE_CLASS = ScriptDefaults.class.getName();
    public static final Class<?> baseClass = ScriptDefaults.class;
    public static final String classpath = ownJar();
    /** null on a JRE: B4 scripts need the game started with a JDK (as for B4 itself) */
    public static final JavaCompiler compiler = findCompiler();

    public static final List<String> IMPORTS = Arrays.asList("java.awt.Color", "java.util.List", "java.util.ArrayList",
        "java.util.Arrays", "java.util.Map", "java.util.HashMap", "java.util.HashSet",
        "java.util.concurrent.ConcurrentHashMap", "java.util.LinkedHashMap", "java.util.LinkedList",
        "java.util.Collections", "java.util.Iterator", "java.util.Comparator", "java.util.concurrent.atomic.AtomicBoolean",
        "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong", "java.util.Random",
        "java.util.regex.Pattern", "java.util.regex.Matcher");
    public static final HashSet<String> BLOCKED = new HashSet<>(Arrays.asList("getClass", ".getSuperclass",
        ".getStackTrace", "ClassLoader(", ".getDeclaredField", ".getField", "ProcessBuilder:", ".getDeclaredMethod",
        "java.", "Package:", "Package.", "sun.", "Runtime.", "System.", "Class:", "Runtime:", "System:", "Class.",
        "com.", ".getConstructor", "printStackTrace", ".getMethod", "Thread:", "jdk.", ".getDeclaredConstructor",
        "ClassLoader:", ".getPackage", "StackWalker:", "javax.", "ClassLoader.", "StackWalker.", "Thread.",
        "ProcessBuilder("));

    /** script -> its module, in load order (game thread) */
    private static final Map<Script, ScriptModule> SCRIPTS = new LinkedHashMap<>();
    /** the modules for the events, readable from any thread */
    private static volatile ScriptModule[] modules = new ScriptModule[0];
    public static final Map<String, String> classNames = new ConcurrentHashMap<>();
    public static final List<Object[]> pendingSettings = new ArrayList<>();
    public static ScriptEvents events;
    public static volatile boolean loading;
    public static int loadedCount, removedCount;
    private static long loadStarted;

    private ScriptManager() { }

    private static String ownJar() {
        try {
            java.net.URL url = ScriptDefaults.class.getProtectionDomain().getCodeSource().getLocation();
            String s = url.toString();
            if (s.startsWith("jar:")) {
                int bang = s.indexOf("!/");
                url = new java.net.URL(s.substring(4, bang == -1 ? s.length() : bang));
            }
            return new File(url.toURI()).getAbsolutePath().replace("%", "%25").replace("+", "%2B");
        } catch (Exception e) {
            return "";
        }
    }

    private static JavaCompiler findCompiler() {
        try {
            return ToolProvider.getSystemJavaCompiler();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The scripts' modules (any thread). */
    public static ScriptModule[] modules() { return modules; }

    public static List<Script> scripts() { return new ArrayList<>(SCRIPTS.keySet()); }

    public static ScriptModule moduleOf(Script s) { return SCRIPTS.get(s); }

    /** The folder scripts are read from (made when missing). */
    public static File folder() {
        ScriptManagerModule m = Gale.modules().get(ScriptManagerModule.class);
        String path = m == null ? "galemod/scripts" : m.folder.get().trim();
        File f = new File(path);
        if (!f.isAbsolute()) f = new File(Gale.config().dir().getParentFile(), path);
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    public static boolean isValidName(String string) {
        if (string.isEmpty()) return false;
        if (string.length() > 14) {
            ChatUtil.print("&7Script &b" + string + "&7 blocked, name &ctoo long&7!");
            return false;
        }
        Module module = Gale.modules().get(string);
        if (module != null && !(module instanceof ScriptModule)) {
            ChatUtil.print("&7Script &b" + string + "&7 blocked, &cconflicting &7module name!");
            return false;
        }
        for (char c : string.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') continue;
            ChatUtil.print("&7Script &b" + string + "&7 blocked, &cinvalid characters&7!");
            return false;
        }
        return true;
    }

    public static void onEnable(Script script) {
        if (events == null) {
            ScriptInvoker.callStatic(baseClass, "protected_setupCache", Void.TYPE);
            events = new ScriptEvents();
            MinecraftForge.EVENT_BUS.register(events);
            ScriptEvents.attachGale();
        }
        ScriptInvoker.call(script, "onEnable");
    }

    public static void onDisable(Script script) {
        if (events != null) {
            boolean none = true;
            for (ScriptModule m : modules) {
                if (!m.isEnabled()) continue;
                none = false;
                break;
            }
            if (none) {
                MinecraftForge.EVENT_BUS.unregister(events);
                ScriptEvents.detachGale();
                events = null;
                ScriptInvoker.callStatic(baseClass, "protected_resetCache", Void.TYPE, false);
            }
        }
        ScriptInvoker.call(script, "onDisable");
    }

    // ------------------------------------------------------------------ loading

    /** What one file of the folder holds: name, source, whether it is encrypted, the hash of name and source. */
    private static final class Job {
        final String name, source, trailer, hash;
        final boolean encrypted;
        Script script;

        Job(String name, String source, boolean encrypted, String trailer) {
            this.name = name;
            this.source = source;
            this.encrypted = encrypted;
            this.trailer = trailer;
            this.hash = ScriptCrypto.md5(name + source);
        }
    }

    /**
     * Reads the folder again: new and changed scripts are compiled (in the background) and loaded, scripts whose
     * file is gone are taken out, unchanged ones keep running. Game thread. False when it cannot start.
     */
    public static boolean reload() {
        removedCount = 0;
        loadedCount = 0;
        if (loading) {
            ChatUtil.print("&cScripts are still loading...");
            return false;
        }
        if (compiler == null) {
            ChatUtil.print("&cCompiler error, JDK not found! &7(start the game with a JDK, as for B4's scripts)");
            return false;
        }
        loading = true;
        loadStarted = System.currentTimeMillis();
        List<Job> jobs = new ArrayList<>();
        try {
            File[] files = folder().listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                HashSet<String> seen = new HashSet<>();
                for (File file : files) {
                    if (!file.isFile() || !file.canRead() || !seen.add(file.getName().toLowerCase())) continue;
                    Job job = read(file);
                    if (job != null) jobs.add(job);
                }
            }
        } catch (Exception e) {
            loading = false;
            Gale.LOG.error("Reading the script folder failed", e);
            return false;
        }
        // compile what changed, off the game thread; then finish on it
        List<Job> compile = new ArrayList<>();
        for (Job j : jobs) if (existing(j) == null) compile.add(j);
        Thread t = new Thread(() -> {
            for (Job j : compile) {
                Script s = new Script(j.name);
                s.encrypted = j.encrypted;
                s.hash = j.hash;
                if (j.encrypted && !s.deriveNames(j.trailer)) s.failed = true;
                else s.compile(j.source);
                j.script = s;
            }
            Scheduler.main(() -> finish(jobs));
        }, "Gale script compiler");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // B4's reading of a script file: the game's default charset, "decrypt - " lines, the safety check
    private static Job read(File file) {
        if (file.getName().startsWith("_") || !file.getName().endsWith(EXTENSION)) return null;
        String name = file.getName().substring(0, file.getName().length() - EXTENSION.length());
        // GaleLink is B4's side of the link: run here, it would report Gale's own state back to Gale as B4's
        if (!isValidName(name) || name.equalsIgnoreCase("GaleLink")) return null;
        boolean encrypted = false;
        String trailer = null;
        List<String> lines = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), Charset.defaultCharset())) {
                if (line.contains("decrypt - \"")) {
                    String payload = ScriptCrypto.decryptLine(line);
                    if (payload != null) {
                        String tr = ScriptCrypto.trailer(payload);
                        payload = ScriptCrypto.source(payload);
                        if (payload != null) {
                            encrypted = true;
                            trailer = tr;
                            lines = Arrays.asList(payload.split("\n"));
                            break;
                        }
                    }
                }
                lines.add(line);
            }
        } catch (Exception e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        if (sb.length() == 0) return null;
        String source = sb.toString();
        if (!ScriptText.isSafe(source, encrypted)) {
            ChatUtil.print("&7Script &b" + name + "&7 blocked, &cunsafe code&7 detected!");
            return null;
        }
        return new Job(name, source, encrypted, trailer);
    }

    private static Script existing(Job j) {
        for (Script s : SCRIPTS.keySet()) if (s.name.equals(j.name) && s.hash.equals(j.hash)) return s;
        return null;
    }

    private static void finish(List<Job> jobs) {
        try {
            HashSet<Script> keep = new HashSet<>();
            Map<String, JsonObject> carried = new LinkedHashMap<>();
            for (Job j : jobs) {
                Script old = existing(j);
                if (old != null) keep.add(old);
            }
            // gone or changed: out (a changed script's state goes over to its new module)
            for (Script s : new ArrayList<>(SCRIPTS.keySet())) {
                if (keep.contains(s)) continue;
                ScriptModule m = SCRIPTS.get(s);
                boolean replaced = false;
                for (Job j : jobs) if (j.name.equals(s.name)) replaced = true;
                if (replaced) carried.put(s.name, Gale.config().snapshot(m));
                else {
                    Gale.config().keep(m);
                    ++removedCount;
                }
                remove(s);
            }
            for (Job j : jobs) {
                if (j.script == null) continue;
                Script s = j.script;
                if (!s.failed) s.load();
                classNames.put(s.className, s.name);
                ScriptModule m = register(s);
                if (m == null) continue;
                ++loadedCount;
                JsonObject state = carried.get(s.name);
                if (state != null) Gale.config().restore(m, state);
                else Gale.config().applySaved(m);
            }
            loadedCount += keep.size();
            Gale.config().markDirty();
            long took = System.currentTimeMillis() - loadStarted;
            if (SCRIPTS.isEmpty()) ChatUtil.print("&7No scripts found. &8(" + folder().getPath() + ")");
            else ChatUtil.print("&7Loaded &b" + SCRIPTS.size() + " &7script" + (SCRIPTS.size() == 1 ? "" : "s") + " in &b" + (took < 1000 ? took + "&7ms" : String.format("%.1f", took / 1000.0) + "&7s") + ".");
        } finally {
            loading = false;
        }
    }

    /** Runs onLoad (collecting its settings) and puts the script's module in. */
    private static ScriptModule register(Script s) {
        pendingSettings.clear();
        ScriptInvoker.call(s, "onLoad");
        StringBuilder description = new StringBuilder();
        for (Object[] o : pendingSettings) {
            if ((Integer) o[0] != 0) continue;
            if (description.length() > 0) description.append(" / ");
            description.append(o[1]);
        }
        ScriptModule m = new ScriptModule(s, description.length() == 0 ? (s.failed ? "B4 脚本（加载出错，看聊天栏）" : "B4 脚本") : description.toString());
        for (Object[] o : pendingSettings) {
            try {
                switch ((Integer) o[0]) {
                    case 1:
                        m.button((String) o[1], (Boolean) o[2]);
                        break;
                    case 2:
                        m.slider((String) o[1], (String) o[2], (Double) o[3], (Double) o[4], (Double) o[5], (Double) o[6]);
                        break;
                    case 3: {
                        String[] modes = (String[]) o[4];
                        if (modes != null && modes.length > 0) m.modes((String) o[1], (Integer) o[3], modes);
                        break;
                    }
                    default:
                }
            } catch (Exception e) {
                Gale.LOG.warn("Script {}: setting {} skipped ({})", s.name, o[1], e.getMessage());
            }
        }
        pendingSettings.clear();
        try {
            Gale.modules().register(m);
        } catch (IllegalArgumentException e) {
            ChatUtil.print("&7Script &b" + s.name + "&7 blocked, &cconflicting &7module name!");
            s.unload();
            return null;
        }
        SCRIPTS.put(s, m);
        modules = SCRIPTS.values().toArray(new ScriptModule[0]);
        return m;
    }

    private static void remove(Script s) {
        ScriptModule m = SCRIPTS.remove(s);
        modules = SCRIPTS.values().toArray(new ScriptModule[0]);
        if (m != null) {
            m.setEnabled(false);
            Gale.modules().unregister(m);
        }
        classNames.remove(s.className);
        s.unload();
    }

    /** Takes every script out (the Script Manager's folder changed, or the game is closing). */
    public static void unloadAll() {
        for (Script s : new ArrayList<>(SCRIPTS.keySet())) {
            Gale.config().keep(SCRIPTS.get(s));
            remove(s);
        }
    }

    /** A script module's setting by its B4 name: exact first, then ignoring case and spaces. */
    static Setting<?> setting(Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return s;
        return m.getSetting(name);
    }
}
