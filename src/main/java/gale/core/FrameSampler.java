package gale.core;

import gale.Gale;
import gale.script.ScriptNames;
import gale.util.ReflectUtil;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Where frame time goes. A background thread looks at the game thread's stack a few hundred times a second and
 * files each look under who was running: a mod (by the jar its class came from: B4, FPSMaster, Gale...), a B4
 * script (by its name when it has registered a Gale event, see ScriptNames), or a part of the game itself (drawing
 * terrain, entities, particles, text, waiting for the graphics card...). The share of looks times the frame time
 * is the time per frame. GC pauses are taken from the JVM separately. Nothing is loaded or changed to find out:
 * classes are only looked up in the class loader's cache.
 */
public final class FrameSampler {
    public static final String GC = "GC 停顿";

    public static final class Row {
        public final String name;
        /** milliseconds per frame, and the share of the frame (0..1) */
        public final double ms, share;

        Row(String name, double ms, double share) {
            this.name = name;
            this.ms = ms;
            this.share = share;
        }
    }

    public static final class Report {
        public final double frameMs, fps, low1Fps, seconds;
        public final int samples;
        public final List<Row> rows;
        /** per row: what inside it took the time (class.method, or a script's method) */
        public final Map<String, List<Row>> details;

        Report(double frameMs, double fps, double low1Fps, double seconds, int samples, List<Row> rows, Map<String, List<Row>> details) {
            this.frameMs = frameMs;
            this.fps = fps;
            this.low1Fps = low1Fps;
            this.seconds = seconds;
            this.samples = samples;
            this.rows = rows;
            this.details = details;
        }
    }

    /** One second of looks. */
    private static final class Window {
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, Map<String, Integer>> details = new HashMap<>();
        int samples;
        long nanos, gcMillis;
        List<Long> frames = Collections.emptyList();
    }

    private static final String[] NOT_MODS = { "java.", "javax.", "sun.", "jdk.", "com.sun.", "org.lwjgl.", "net.minecraft.", "net.minecraftforge.",
        "cpw.mods.", "com.google.", "io.netty.", "org.apache.", "com.mojang.", "paulscode.", "org.spongepowered.", "net.java.", "gnu.trove.", "org.objectweb.",
        "ibxm.", "net.fabricmc.", "LZMA.", "argo.", "oshi." };
    private static final Map<String, String> OWNERS = new ConcurrentHashMap<>();
    private static Map<String, String> modByPath;

    private static volatile Thread game, worker;
    private static volatile int hz = 200;
    private static volatile Report recent, longer;
    private static final List<Long> FRAMES = new ArrayList<>();
    private static long lastFrame;

    private FrameSampler() { }

    public static boolean isRunning() { return worker != null; }

    /** Starts looking (or changes the rate). */
    public static synchronized void start(int samplesPerSecond) {
        hz = Math.max(10, Math.min(2000, samplesPerSecond));
        if (worker != null) return;
        Thread t = new Thread(FrameSampler::run, "Gale frame sampler");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        worker = t;
        t.start();
    }

    public static synchronized void stop() {
        worker = null;
        recent = longer = null;
        synchronized (FRAMES) {
            FRAMES.clear();
        }
        lastFrame = 0;
    }

    /** Called at the start of every frame, on the game thread. */
    public static void frame() {
        if (worker == null) return;
        game = Thread.currentThread();
        long now = System.nanoTime();
        if (lastFrame != 0) {
            synchronized (FRAMES) {
                FRAMES.add(now - lastFrame);
            }
        }
        lastFrame = now;
    }

    /** The last 3 seconds (null until the first second is in). */
    public static Report recent() { return recent; }

    /** The last 10 seconds. */
    public static Report longer() { return longer; }

    // ------------------------------------------------------------------ the looking thread

    private static void run() {
        Deque<Window> windows = new ArrayDeque<>();
        Window w = new Window();
        long start = System.nanoTime(), next = start, gc = gcMillis();
        while (worker == Thread.currentThread()) {
            Thread g = game;
            if (g != null) {
                try {
                    StackTraceElement[] st = g.getStackTrace();
                    if (st.length > 0) file(w, st);
                } catch (Throwable t) {
                    Gale.LOG.debug("Sampling failed", t);
                }
            }
            long now = System.nanoTime();
            if (now - start >= 1_000_000_000L) {
                w.nanos = now - start;
                long gcNow = gcMillis();
                w.gcMillis = gcNow - gc;
                gc = gcNow;
                synchronized (FRAMES) {
                    w.frames = new ArrayList<>(FRAMES);
                    FRAMES.clear();
                }
                windows.addLast(w);
                while (windows.size() > 10) windows.removeFirst();
                List<Window> all = new ArrayList<>(windows);
                recent = combine(all.subList(Math.max(0, all.size() - 3), all.size()));
                longer = combine(all);
                w = new Window();
                start = now;
            }
            next += 1_000_000_000L / hz;
            long sleep = next - System.nanoTime();
            // every look stops the game thread for a moment: behind schedule, skip ahead instead of looking back to back
            if (sleep < 1_000_000L) {
                next = System.nanoTime() + 1_000_000L;
                sleep = 1_000_000L;
            }
            LockSupport.parkNanos(sleep);
        }
    }

    private static void file(Window w, StackTraceElement[] st) {
        // only what runs inside the game loop: below it are the launcher's and Java's own frames
        for (int i = 0; i < st.length; i++) {
            if (st[i].getClassName().equals("net.minecraft.client.Minecraft") && (st[i].getMethodName().equals("run") || st[i].getMethodName().equals("func_99999_d"))) {
                st = Arrays.copyOf(st, Math.max(1, i));
                break;
            }
        }
        String bucket = null, detail = null;
        // Gale's own background frame cap sleeping is idle time, not work
        if (st[0].getClassName().equals("java.lang.Thread") && st[0].getMethodName().equals("sleep")) {
            for (StackTraceElement f : st) {
                if (f.getClassName().equals("gale.module.impl.optimize.Performance")) {
                    bucket = "后台限帧";
                    detail = "Performance.background";
                    break;
                }
            }
        }
        for (StackTraceElement f : st) {
            if (bucket != null) break;
            String owner = ownerOf(f.getClassName());
            if (owner == null) continue;
            if (owner.startsWith("script:")) {
                String cls = owner.substring(7), name = ScriptNames.of(cls);
                bucket = "脚本 " + (name != null ? name : "（没连 Gale 的）");
                detail = f.getMethodName();
            } else {
                bucket = owner;
                detail = simple(f.getClassName()) + "." + f.getMethodName();
            }
            break;
        }
        if (bucket == null) {
            bucket = phase(st);
            for (StackTraceElement f : st) {
                if (f.getClassName().startsWith("net.minecraft")) {
                    detail = simple(f.getClassName()) + "." + f.getMethodName();
                    break;
                }
            }
            if (detail == null) detail = simple(st[0].getClassName()) + "." + st[0].getMethodName();
        }
        w.counts.merge(bucket, 1, Integer::sum);
        w.details.computeIfAbsent(bucket, k -> new HashMap<>()).merge(detail, 1, Integer::sum);
        w.samples++;
    }

    private static String simple(String cls) { return cls.substring(cls.lastIndexOf('.') + 1); }

    // ------------------------------------------------------------------ who runs a class

    /** The mod or script a class belongs to ("script:<class>" for scripts), or null for the game, Forge and Java. */
    static String ownerOf(String cls) {
        String c = OWNERS.get(cls);
        if (c == null) {
            c = findOwner(cls);
            OWNERS.put(cls, c == null ? "" : c);
        }
        return c.isEmpty() ? null : c;
    }

    private static String findOwner(String cls) {
        if (cls.startsWith("gale.")) return Gale.NAME;
        String outer = ScriptNames.outer(cls);
        if (ScriptNames.isScript(outer)) return "script:" + outer;
        for (String p : NOT_MODS) if (cls.startsWith(p)) return null;
        String mod = modOf(outer);
        if (mod != null) return mod;
        int dot = outer.lastIndexOf('.');
        return dot > 0 ? outer.substring(0, dot) : outer;
    }

    /** The mod whose jar the class came from, looked up in the class loader's cache only (no loading). */
    private static String modOf(String cls) {
        try {
            LaunchClassLoader loader = Launch.classLoader;
            Map<String, Class<?>> cache = ReflectUtil.get(ReflectUtil.field(LaunchClassLoader.class, "cachedClasses"), loader);
            Class<?> k = cache == null ? null : cache.get(cls);
            CodeSource cs = k == null ? null : k.getProtectionDomain().getCodeSource();
            URL url = cs == null ? null : cs.getLocation();
            if (url == null) return null;
            String path = path(url);
            if (path == null) return null;
            String mod = mods().get(path);
            return mod != null ? mod : new File(path).getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String path(URL url) {
        try {
            String s = url.toString();
            if (s.startsWith("jar:")) s = s.substring(4, s.indexOf("!/") > 0 ? s.indexOf("!/") : s.length());
            return new File(new URL(s).toURI()).getCanonicalPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized Map<String, String> mods() {
        if (modByPath == null) {
            Map<String, String> m = new HashMap<>();
            for (ModContainer c : Loader.instance().getActiveModList()) {
                try {
                    File src = c.getSource();
                    if (src != null) m.put(src.getCanonicalPath(), c.getName());
                } catch (Exception ignored) {
                    // a mod without a file
                }
            }
            modByPath = m;
        }
        return modByPath;
    }

    /** The part of the game a stack is in, from the innermost frame out. */
    static String phase(StackTraceElement[] st) {
        for (StackTraceElement f : st) {
            String c = f.getClassName(), m = f.getMethodName();
            if (c.startsWith("org.lwjgl.opengl.")) {
                if (m.contains("wapBuffers") || c.endsWith(".Display") && m.equals("update")) return "等显卡（交换画面）";
                if (c.endsWith(".Sync") || c.endsWith(".Display") && m.equals("sync")) return "限帧等待";
                continue;
            }
            if (c.equals("java.lang.Thread") && (m.equals("sleep") || m.equals("yield"))) return "限帧等待";
            if (!c.startsWith("net.minecraft")) continue;
            if (c.startsWith("net.minecraft.client.renderer.chunk.")) return "区块重建";
            if (c.startsWith("net.minecraft.client.particle.")) return "粒子";
            if (c.startsWith("net.minecraft.client.renderer.tileentity.")) return "方块实体";
            if (c.equals("net.minecraft.client.gui.FontRenderer")) return "文字";
            if (c.startsWith("net.minecraft.client.model.") || c.startsWith("net.minecraft.client.renderer.entity.")) return "画实体";
            if (c.equals("net.minecraft.client.renderer.ItemRenderer")) return "手和手里的东西";
            if (c.startsWith("net.minecraft.client.gui.") || c.startsWith("net.minecraftforge.client.GuiIngameForge")) return "HUD 和界面";
            if (c.equals("net.minecraft.client.renderer.RenderGlobal")) {
                switch (m) {
                    case "renderBlockLayer": case "func_174977_a": case "func_174982_a": return "画地形";
                    case "setupTerrain": case "func_174970_a": return "算哪些区块看得见";
                    case "updateChunks": case "func_174967_a": return "区块重建";
                    case "renderSky": case "func_174976_a": case "func_174968_a": case "renderClouds": case "func_180447_b": return "天空和云";
                    case "renderEntities": case "func_180446_a": return "画实体";
                    default: return "画世界（其他）";
                }
            }
            if (c.equals("net.minecraft.client.renderer.EntityRenderer")) {
                switch (m) {
                    case "updateLightmap": case "func_78472_g": return "光照";
                    case "renderHand": case "func_78476_b": return "手和手里的东西";
                    case "getMouseOver": case "func_78473_a": return "准星判定";
                    default: return "画世界（其他）";
                }
            }
            if (c.startsWith("net.minecraft.client.network.") || c.startsWith("net.minecraft.network.")) return "处理数据包";
            if (c.equals("net.minecraft.client.Minecraft") && (m.equals("runTick") || m.equals("func_71407_l"))) return "游戏 tick";
        }
        return "其他";
    }

    private static long gcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, b.getCollectionTime());
        return t;
    }

    // ------------------------------------------------------------------ adding up

    private static Report combine(List<Window> ws) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Map<String, Integer>> details = new HashMap<>();
        List<Long> frames = new ArrayList<>();
        int samples = 0;
        long nanos = 0, gc = 0;
        for (Window w : ws) {
            for (Map.Entry<String, Integer> e : w.counts.entrySet()) counts.merge(e.getKey(), e.getValue(), Integer::sum);
            for (Map.Entry<String, Map<String, Integer>> e : w.details.entrySet()) {
                Map<String, Integer> d = details.computeIfAbsent(e.getKey(), k -> new HashMap<>());
                for (Map.Entry<String, Integer> x : e.getValue().entrySet()) d.merge(x.getKey(), x.getValue(), Integer::sum);
            }
            frames.addAll(w.frames);
            samples += w.samples;
            nanos += w.nanos;
            gc += w.gcMillis;
        }
        double seconds = nanos / 1e9, wallMs = nanos / 1e6;
        int n = Math.max(1, frames.size());
        double frameMs = frames.isEmpty() ? wallMs : wallMs / n;
        double gcPerFrame = Math.min(frameMs, gc / (double) n), sampledPerFrame = frameMs - gcPerFrame;
        List<Row> rows = new ArrayList<>();
        Map<String, List<Row>> detailRows = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double share = samples == 0 ? 0 : e.getValue() / (double) samples;
            rows.add(new Row(e.getKey(), share * sampledPerFrame, share * sampledPerFrame / frameMs));
            List<Row> d = new ArrayList<>();
            for (Map.Entry<String, Integer> x : details.get(e.getKey()).entrySet()) {
                double s = x.getValue() / (double) samples;
                d.add(new Row(x.getKey(), s * sampledPerFrame, s * sampledPerFrame / frameMs));
            }
            d.sort((a, b) -> Double.compare(b.ms, a.ms));
            detailRows.put(e.getKey(), d);
        }
        if (gc > 0) rows.add(new Row(GC, gcPerFrame, gcPerFrame / frameMs));
        rows.sort((a, b) -> Double.compare(b.ms, a.ms));
        long[] sorted = new long[frames.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = frames.get(i);
        Arrays.sort(sorted);
        double p99 = sorted.length == 0 ? frameMs : sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.99))] / 1e6;
        return new Report(frameMs, 1000 / frameMs, 1000 / p99, seconds, samples, rows, detailRows);
    }

    /** The report as text: every row, and the top of what is inside the biggest ones. */
    public static List<String> describe(Report r) {
        List<String> out = new ArrayList<>();
        out.add(String.format("最近 %.0f 秒：每帧 %.2f ms，平均 %.0f FPS，1%% 低帧 %.0f FPS（%d 次取样）", r.seconds, r.frameMs, r.fps, r.low1Fps, r.samples));
        for (Row row : r.rows) {
            out.add(String.format("  %-16s %6.2f ms  %4.1f%%", row.name, row.ms, row.share * 100));
            List<Row> d = r.details.get(row.name);
            if (d == null || row.share < 0.02) continue;
            for (int i = 0; i < Math.min(5, d.size()); i++) {
                if (d.get(i).share < 0.005) break;
                out.add(String.format("      %-40s %6.2f ms", d.get(i).name, d.get(i).ms));
            }
        }
        return out;
    }
}
