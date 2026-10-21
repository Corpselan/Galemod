package gale.b4script;

import gale.util.ChatUtil;
import net.minecraft.launchwrapper.Launch;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One B4 script: compiled the way B4 compiles it (its imports, wrapped in a class with a random 200-letter name that
 * extends ScriptDefaults, line numbers counted the same way in errors). Unlike B4 the compiling is split from the
 * loading: {@link #compile} may run on another thread (javac is slow and would freeze the game), {@link #load}
 * defines the class and makes the instance on the game thread.
 */
public class Script {
    public boolean failed;
    public Class<?> scriptClass;
    public String hash;
    public Object instance;
    public URLClassLoader classLoader;
    public String name;
    public String[] encryptedNames;
    public String className;
    public boolean encrypted;
    public boolean loadString;
    public List<String> loadErrors;
    /** where this script's classes were compiled to (a folder of its own) */
    private File outDir;

    public Script(String string) {
        this.name = string;
        this.className = ScriptText.randomClassName(200);
    }

    public String methodName(ScriptEvent event) {
        if (!this.encrypted || this.encryptedNames == null) return event.getNormalName();
        return this.encryptedNames[event.ordinal()];
    }

    public void printErrors(List<String> list) {
        if (!ScriptUtil.inGame()) return;
        if (this.encrypted) {
            // B4 hides an encrypted script's errors (it encrypts them for its developers); Gale says only that it failed
            ChatUtil.print(list.get(0));
            ChatUtil.print(" &7err: &cHidden due to encryption");
            return;
        }
        for (String string : list) ChatUtil.print(string);
    }

    public void reportError(Exception exception, boolean runtime) {
        if (exception.getCause() == null) return;
        List<String> lines = new ArrayList<>();
        lines.add(runtime ? "&7Runtime error during script &b" + this.name : "&7Error loading script &b" + this.name);
        lines.add(" &7err: &c" + exception.getCause().getClass().getSimpleName());
        StackTraceElement[] trace = exception.getCause().getStackTrace();
        if (trace.length > 0) {
            StackTraceElement at = trace[0];
            for (StackTraceElement e : trace) {
                if (!e.getClassName().equalsIgnoreCase(this.className)) continue;
                at = e;
                break;
            }
            lines.add(" &7line: &c" + (at.getLineNumber() - 20));
            lines.add(" &7src: &c" + (runtime ? at.getMethodName() : "init"));
        }
        ScriptUtil.LOGGER.warn("[Gale scripts] {} {}", this.name, exception.getCause().toString());
        this.printErrors(lines);
        if (!runtime) {
            if (this.loadErrors == null) this.loadErrors = new ArrayList<>();
            this.loadErrors.addAll(lines);
        }
    }

    public boolean deriveNames(String string) {
        if (string == null || string.length() != 32) return false;
        ScriptEvent[] events = ScriptEvent.values();
        this.encryptedNames = new String[events.length];
        for (int i = 0; i < events.length; ++i) {
            String derived = events[i].deriveEncryptedName(string);
            if (derived == null) {
                this.encryptedNames = null;
                return false;
            }
            this.encryptedNames[i] = derived;
        }
        return true;
    }

    /** Compiles the source (any thread). False when javac found errors (they are printed and kept in loadErrors). */
    public boolean compile(String source) {
        if (ScriptManager.compiler == null) {
            this.failed = true;
            return false;
        }
        outDir = new File(ScriptManager.tempDir, this.className.substring(0, 24) + System.nanoTime());
        outDir.mkdirs();
        JavaFileManager fileManager = null;
        try {
            Diagnostics diagnostics = new Diagnostics(this);
            fileManager = ScriptManager.compiler.getStandardFileManager(diagnostics, null, null);
            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(outDir.getAbsolutePath());
            options.add("-XDuseUnsharedTable");
            Object deobf = Launch.blackboard == null ? null : Launch.blackboard.get("fml.deobfuscatedEnvironment");
            if (!Boolean.TRUE.equals(deobf)) {
                // in the real game only Gale's jar: the scripts see B4's API (Gale's copy), as B4 gives them its own jar
                options.add("-classpath");
                String classpath = ScriptManager.classpath;
                try {
                    classpath = URLDecoder.decode(classpath, "UTF-8");
                } catch (UnsupportedOperationException | UnsupportedEncodingException ignored) {
                }
                options.add(classpath);
            }
            StringBuilder sb = new StringBuilder();
            for (String imp : ScriptManager.IMPORTS) sb.append("import ").append(imp).append(";\n");
            sb.append("public class ").append(this.className).append(" extends ").append(ScriptManager.BASE_CLASS).append(" {\n").append(source).append("\n}");
            Boolean ok = ScriptManager.compiler.getTask(null, fileManager, diagnostics, options, null, Collections.singletonList(new Source(this.className, sb.toString()))).call();
            if (!Boolean.TRUE.equals(ok)) this.failed = true;
            return !this.failed;
        } catch (Exception e) {
            this.failed = true;
            ScriptUtil.LOGGER.warn("[Gale scripts] compiling {} failed", this.name, e);
            return false;
        } finally {
            if (fileManager != null) {
                try {
                    fileManager.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Defines the compiled class and makes the script's instance (game thread). Field initialisers run here. */
    public boolean load() {
        if (this.failed || outDir == null) return false;
        try {
            this.classLoader = new URLClassLoader(new URL[]{ outDir.toURI().toURL() }, Launch.classLoader != null ? Launch.classLoader : getClass().getClassLoader());
            this.scriptClass = this.classLoader.loadClass(this.className);
            // every nested class now, so the files can go
            File[] inner = outDir.listFiles((dir, n) -> n.startsWith(this.className + "$") && n.endsWith(".class"));
            if (inner != null) for (File f : inner) this.classLoader.loadClass(f.getName().substring(0, f.getName().length() - ".class".length()));
            this.instance = this.scriptClass.getDeclaredConstructor().newInstance();
            return true;
        } catch (Exception e) {
            this.failed = true;
            this.reportError(e, false);
            if (e.getCause() == null) ScriptUtil.LOGGER.warn("[Gale scripts] loading {} failed", this.name, e);
            return false;
        } finally {
            deleteClassFiles();
        }
    }

    public void deleteClassFiles() {
        if (outDir == null) return;
        File[] files = outDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        outDir.delete();
    }

    public void unload() {
        this.scriptClass = null;
        this.instance = null;
        this.encryptedNames = null;
        if (this.classLoader != null) {
            try {
                this.classLoader.close();
            } catch (IOException ignored) {
            }
            this.classLoader = null;
        }
        this.deleteClassFiles();
    }

    static class Source extends SimpleJavaFileObject {
        final String code;

        Source(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) { return this.code; }
    }

    /** javac's messages, cut down the way B4 cuts them (B4's own wording, line numbers of the script file). */
    static class Diagnostics implements DiagnosticListener<JavaFileObject> {
        final Script script;

        Diagnostics(Script script) { this.script = script; }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) script.failed = true;
            String msg = diagnostic.getMessage(null);
            if (msg.equals("unchecked or unsafe operations")) return;
            if (msg.contains("SpongePowered") || msg.contains("recompile with")) return;
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
            if (msg.contains("\n")) msg = msg.split("\n")[0];
            List<String> lines = new ArrayList<>();
            lines.add("&7Error loading script &b" + this.script.name);
            if (msg.contains("ScriptDefaults")) {
                if (msg.startsWith("incompatible types: ")) {
                    StringBuilder sb = new StringBuilder();
                    for (String word : msg.split(" ")) {
                        if (word.contains("ScriptDefaults.")) word = word.substring(word.lastIndexOf('.') + 1);
                        sb.append(word).append(' ');
                    }
                    msg = sb.toString();
                } else {
                    msg = msg.contains("no suitable constructor found") ? "no suitable constructor found"
                        : msg.contains("cannot be applied to given types") ? "method cannot be applied to given types"
                        : msg.contains("bad operand type") ? "bad operand type for unary operator"
                        : msg.contains("is not public in") || msg.contains("has private access") || msg.contains("has protected access") ? "cannot find symbol"
                        : msg.contains("is already defined") || msg.contains("incomparable types:") ? msg.replace(ScriptDefaults.class.getName() + ".", "")
                        : msg;
                }
            } else if (msg.contains("cannot access net.")) {
                msg = "no suitable constructor found";
            }
            if (msg.contains(this.script.className)) {
                int n = msg.indexOf(" in class");
                if (n != -1) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(msg, 0, n);
                    int n2 = msg.indexOf(" ", n + " in class ".length());
                    if (n2 != -1) sb.append(msg.substring(n2));
                    msg = sb.toString();
                }
                if (msg.contains(this.script.className + ".")) msg = msg.replace(this.script.className + ".", "");
                msg = msg.replace(this.script.className, this.script.name);
            }
            lines.add(" &7err: &c" + msg);
            JavaFileObject source = diagnostic.getSource();
            if (source != null) {
                try {
                    String text = source.getCharContent(true).toString();
                    int pos = (int) diagnostic.getPosition();
                    if (pos >= 0) {
                        int line = text.substring(0, pos).split("\\n").length - 20;
                        if (line >= 0) {
                            lines.add(" &7line: &c" + line);
                            String src = text.substring(pos).split("\\n")[0].trim();
                            if (!src.isEmpty()) lines.add(" &7src: &c" + src);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            ScriptUtil.LOGGER.warn("[Gale scripts] {}: {}", this.script.name, String.join(" ", lines));
            this.script.printErrors(lines);
            if (this.script.loadErrors == null) this.script.loadErrors = new ArrayList<>();
            this.script.loadErrors.addAll(lines);
        }
    }
}
