import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes a watermark-only extension out of Furiah's Visuals: only the WaterMark and UI sections are registered, it is
 * named "FV Watermark", keeps its settings in config/fvwatermark.json, and leaves out the weapon models and sky shaders.
 * With a third argument (a folder of compiled classes, the Bed Tracker) those are added and the Bed Tracker registered.
 * args: in.meowtils out.meowtils [classes]
 */
public final class MakeFvWatermark {
    static final String CORE = "meowtils/furiahvisuals/core/FuriahsVisuals";
    static final String WRAPPER = "meowtils/furiahvisuals/FuriahVisualsModule";
    static final String[] DROP = { "assets/furiahsvisuals/weapons/", "assets/furiahsvisuals/shaders/sky/" };
    /** what the island shows at its left instead of FV */
    static final String INITIALS = "CP";
    static final String FV_ITEM = "meowtils/furiahvisuals/module/watermark/FvItem.class";
    static final java.util.List<String> LABELS = java.util.Arrays.asList(FV_ITEM, "meowtils/furiahvisuals/module/watermark/ModuleListWidget.class",
        "meowtils/furiahvisuals/gui/GuiColors.class");
    static final Map<String, String> labelStrings = new HashMap<>();
    /** the island: its message icons go through BedArt.icon (the Bed Tracker's breaking scene) */
    static final String RENDERER = "meowtils/furiahvisuals/module/watermark/IslandRenderer";
    static final String DRAW = "meowtils/furiahvisuals/module/watermark/IslandDraw";
    static final String ICON_DESC = "(Lmeowtils/furiahvisuals/module/watermark/IslandIcons$Icon;FFFI)V";

    public static void main(String[] args) throws Exception {
        Map<String, String> coreStrings = new HashMap<>(), wrapperStrings = new HashMap<>();
        coreStrings.put("furiahsvisuals.json", "fvwatermark.json");
        coreStrings.put("furiahsvisuals/presets", "fvwatermark/presets");
        wrapperStrings.put("Furiah's Visuals", "FV Watermark");
        wrapperStrings.put("Furiah's Visuals 1.3.0, fully offline. Its own menu (Right Shift by default, or /furiah) has every section and setting.",
            "Furiah's Visuals 1.3.0's watermark on its own (only the WaterMark and UI sections). Its menu: Right Shift, or /furiah.");
        // the initials at the left of the island: "FV" -> the two letters in INITIALS (drawn one letter at a time)
        labelStrings.put("FV", INITIALS);
        long kept = 0, dropped = 0;
        try (ZipFile in = new ZipFile(args[0]); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                boolean drop = false;
                for (String d : DROP) drop |= e.getName().startsWith(d);
                if (drop) {
                    dropped += e.getSize();
                    continue;
                }
                byte[] data = read(in.getInputStream(e));
                if (e.getName().equals(CORE + ".class")) data = patch(data, coreStrings, true);
                else if (e.getName().equals(WRAPPER + ".class")) data = patch(data, wrapperStrings, false);
                else if (LABELS.contains(e.getName())) data = patch(data, labelStrings, false);
                else if (e.getName().equals(RENDERER + ".class")) data = patch(data, new HashMap<String, String>(), false);
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
                kept += data.length;
            }
            // added classes: args[2] is a folder of compiled classes
            if (args.length > 2) {
                java.nio.file.Path root = java.nio.file.Paths.get(args[2]);
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) java.nio.file.Files.walk(root)::iterator) {
                    if (!f.toString().endsWith(".class")) continue;
                    String name = root.relativize(f).toString().replace(java.io.File.separatorChar, '/');
                    out.putNextEntry(new ZipEntry(name));
                    out.write(java.nio.file.Files.readAllBytes(f));
                    out.closeEntry();
                    System.out.println("added " + name);
                }
            }
        }
        System.out.println("kept " + kept / 1024 / 1024 + " MB, left out " + dropped / 1024 / 1024 + " MB");
    }

    static byte[] patch(byte[] data, Map<String, String> strings, boolean core) {
        ClassReader r = new ClassReader(data);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] replaced = { 0 };
        r.accept(new ClassVisitor(Opcodes.ASM5, w) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (core && name.equals("registerModules") && desc.equals("()V")) {
                    mv.visitCode();
                    register(mv, "watermark", "meowtils/furiahvisuals/module/watermark/WaterMarkModule");
                    register(mv, "ui", "meowtils/furiahvisuals/module/ui/UiModule");
                    // the Bed Tracker (added classes; it leaves itself out without Gale)
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(Opcodes.GETFIELD, CORE, "modules", "Lmeowtils/furiahvisuals/core/ModuleManager;");
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "meowtils/furiahvisuals/module/watermark/BedTrackerModule", "addTo", "(Lmeowtils/furiahvisuals/core/ModuleManager;)V", false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    replaced[0]++;
                    return null;
                }
                boolean letters = r.getClassName().equals(FV_ITEM.replace(".class", ""));
                boolean notice = r.getClassName().equals(RENDERER) && name.equals("drawNotice");
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                        // d.icon(icon, x, y, size, color) -> BedArt.icon(d, icon, x, y, size, color): the same stack
                        if (notice && opcode == Opcodes.INVOKEVIRTUAL && owner.equals(DRAW) && mname.equals("icon") && mdesc.equals(ICON_DESC)) {
                            replaced[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "meowtils/furiahvisuals/module/watermark/BedArt", "icon",
                                "(L" + DRAW + ";" + ICON_DESC.substring(1), false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        // FvItem pushes its two letters as chars ('F' = 70, 'V' = 86); nothing else there is 70 or 86
                        if (letters && opcode == Opcodes.BIPUSH && (operand == 'F' || operand == 'V')) {
                            operand = operand == 'F' ? INITIALS.charAt(0) : INITIALS.charAt(1);
                            replaced[0]++;
                        }
                        super.visitIntInsn(opcode, operand);
                    }

                    @Override
                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof String && strings.containsKey(cst)) {
                            cst = strings.get(cst);
                            replaced[0]++;
                        }
                        super.visitLdcInsn(cst);
                    }
                };
            }
        }, 0);
        System.out.println(r.getClassName() + ": " + replaced[0] + " changes");
        return w.toByteArray();
    }

    /** this.field = new type(); this.modules.register(this.field); */
    static void register(MethodVisitor mv, String field, String type) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE, field, "L" + type + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE, "modules", "Lmeowtils/furiahvisuals/core/ModuleManager;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE, field, "L" + type + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "meowtils/furiahvisuals/core/ModuleManager", "register", "(Lmeowtils/furiahvisuals/module/Module;)V", false);
    }

    static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
